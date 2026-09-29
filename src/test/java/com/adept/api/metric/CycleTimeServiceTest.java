package com.adept.api.metric;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.deployment.DeploymentRepository;
import com.adept.api.incident.IncidentRepository;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.metric.dto.CycleTimePeriodDto;
import com.adept.api.metric.dto.CycleTimeResponse;
import com.adept.api.metric.dto.CycleTimeStageDto;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.pullrequest.PullRequestRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.RepositoryScopeService;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleTimeServiceTest {

    private static final Instant WEEK_START = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant WEEK_END = Instant.parse("2026-09-28T00:00:00Z");

    @Mock private MetricSnapshotRepository metricSnapshotRepository;
    @Mock private GitRepositoryRepository gitRepositoryRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    @Mock private RepositoryScopeService repositoryScopeService;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private DeploymentRepository deploymentRepository;
    @Mock private PullRequestRepository pullRequestRepository;
    @Mock private IncidentRepository incidentRepository;

    private MetricService metricService;
    private CycleTimeService cycleTimeService;
    private UUID workspaceId;
    private UUID repositoryId;
    private GitRepository repository;
    private AuthenticatedPrincipal manager;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        manager = new AuthenticatedPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), workspaceId, MembershipRole.MANAGER, 1);

        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setTimezone("UTC");
        repository = new GitRepository();
        repository.setId(repositoryId);
        repository.setWorkspace(workspace);
        repository.setTrackingEnabled(true);
        repository.setArchived(false);

        metricService = new MetricService(
            metricSnapshotRepository,
            gitRepositoryRepository,
            projectRepository,
            projectRepositoryLinkRepository,
            repositoryScopeService,
            workspaceRepository,
            deploymentRepository,
            pullRequestRepository,
            incidentRepository
        );
        cycleTimeService = new CycleTimeService(metricService, metricSnapshotRepository);
        lenient().when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        lenient().when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
    }

    @Test
    void poolsStageMediansAndFindsTheBottleneck() {
        Instant tuesday = WEEK_START.plusSeconds(86_400);
        Instant wednesday = WEEK_START.plusSeconds(2 * 86_400);
        List<MetricSnapshot> days = new ArrayList<>();
        days.add(snapshot(MetricType.PR_CODING_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-1", tuesday, 6.0, true),
            observation("pr-2", wednesday, 2.0, true),
            observation("pr-3", wednesday, 1.0, false))));
        days.add(snapshot(MetricType.PR_PICKUP_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-1", tuesday, 44.0, true),
            observation("pr-2", wednesday, 24.0, true))));
        // A second snapshot repeating pr-1 (for example a DAY bucket overlap) is counted once.
        days.add(snapshot(MetricType.PR_PICKUP_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-1", tuesday, 44.0, true))));
        days.add(snapshot(MetricType.PR_REVIEW_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-1", tuesday, 6.0, true))));
        // Merged outside the requested range.
        days.add(snapshot(MetricType.PR_DEPLOY_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-9", WEEK_END.plusSeconds(60), 99.0, false))));
        when(metricSnapshotRepository.findSnapshots(
            workspaceId, List.of(repositoryId), MetricGranularity.DAY,
            CycleTimeService.CALCULATION_VERSION, WEEK_START, WEEK_END)).thenReturn(days);

        CycleTimeResponse response = cycleTimeService.getCycleTime(
            manager, null, null, null, WEEK_START, WEEK_END);

        assertThat(response.granularity()).isEqualTo(MetricGranularity.WEEK);
        assertThat(response.pullRequestCount()).isEqualTo(3);
        assertThat(response.unreviewedPullRequestCount()).isOne();
        assertThat(response.bottleneck()).isEqualTo(CycleTimeStage.PICKUP);
        assertThat(response.stages()).extracting(CycleTimeStageDto::stage)
            .containsExactly(CycleTimeStage.values());
        CycleTimeStageDto pickup = response.stages().get(1);
        assertThat(pickup.medianHours()).isEqualByComparingTo("34.00");
        assertThat(pickup.sampleSize()).isEqualTo(2);
        assertThat(response.stages().get(3).sampleSize()).isZero();

        assertThat(response.series()).hasSize(1);
        assertThat(response.series().get(0).periodStart()).isEqualTo(WEEK_START);
        assertThat(response.series().get(0).periodEnd()).isEqualTo(WEEK_END);
        assertThat(response.series().get(0).pullRequestCount()).isEqualTo(3);
        assertThat(response.series().get(0).stages().get(1).medianHours()).isEqualByComparingTo("34.00");
    }

    @Test
    void emptyScopeReturnsZeroStagesWithoutBottleneck() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());

        CycleTimeResponse response = cycleTimeService.getCycleTime(
            manager, null, null, MetricGranularity.DAY, WEEK_START, WEEK_END);

        assertThat(response.repositoryCount()).isZero();
        assertThat(response.bottleneck()).isNull();
        assertThat(response.stale()).isTrue();
        assertThat(response.stages()).hasSize(4).allMatch(stage -> stage.sampleSize() == 0);
        assertThat(response.series()).isEmpty();
        assertThat(response.unreviewedPullRequestCount()).isZero();
    }

    @Test
    void seriesCoversEveryWorkspaceWeekInTheRangeIncludingQuietAndPartialWeeks() {
        workspace.setTimezone("Asia/Colombo");
        Instant from = Instant.parse("2026-09-02T06:30:00Z");
        Instant to = Instant.parse("2026-09-28T04:30:00Z");
        // Monday 2026-09-14 02:00 in Colombo is still Sunday evening in UTC.
        Instant mergedEarlyMonday = Instant.parse("2026-09-13T20:30:00Z");
        when(metricSnapshotRepository.findSnapshots(
            workspaceId, List.of(repositoryId), MetricGranularity.DAY,
            CycleTimeService.CALCULATION_VERSION, from, to)).thenReturn(List.of(
                snapshot(MetricType.PR_CODING_TIME_HOURS, MetricGranularity.DAY, from, to, List.of(
                    observation("pr-1", mergedEarlyMonday, 2.0, false)))));

        CycleTimeResponse response = cycleTimeService.getCycleTime(
            manager, null, null, MetricGranularity.WEEK, from, to);

        // Monday midnight in Colombo is 18:30 UTC on the Sunday before.
        assertThat(response.series()).extracting(CycleTimePeriodDto::periodStart).containsExactly(
            Instant.parse("2026-08-30T18:30:00Z"),
            Instant.parse("2026-09-06T18:30:00Z"),
            Instant.parse("2026-09-13T18:30:00Z"),
            Instant.parse("2026-09-20T18:30:00Z"),
            Instant.parse("2026-09-27T18:30:00Z"));
        assertThat(response.series()).extracting(CycleTimePeriodDto::pullRequestCount)
            .containsExactly(0, 0, 1, 0, 0);
        assertThat(response.series().get(4).periodEnd()).isEqualTo(Instant.parse("2026-10-04T18:30:00Z"));
    }

    @Test
    void daySeriesStartsANewPeriodEachCalendarDay() {
        Instant from = Instant.parse("2026-09-21T10:00:00Z");
        Instant to = Instant.parse("2026-09-23T09:00:00Z");
        when(metricSnapshotRepository.findSnapshots(
            workspaceId, List.of(repositoryId), MetricGranularity.DAY,
            CycleTimeService.CALCULATION_VERSION, from, to)).thenReturn(List.of());

        CycleTimeResponse response = cycleTimeService.getCycleTime(
            manager, null, null, MetricGranularity.DAY, from, to);

        assertThat(response.series()).extracting(CycleTimePeriodDto::periodStart).containsExactly(
            Instant.parse("2026-09-21T00:00:00Z"),
            Instant.parse("2026-09-22T00:00:00Z"),
            Instant.parse("2026-09-23T00:00:00Z"));
        assertThat(response.series()).allMatch(period -> period.pullRequestCount() == 0);
    }

    @Test
    void zeroMinuteStagesAreNotABottleneckAndMalformedObservationsAreSkipped() {
        Instant tuesday = WEEK_START.plusSeconds(86_400);
        when(metricSnapshotRepository.findSnapshots(
            workspaceId, List.of(repositoryId), MetricGranularity.DAY,
            CycleTimeService.CALCULATION_VERSION, WEEK_START, WEEK_END)).thenReturn(List.of(
                snapshot(MetricType.PR_CODING_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
                    observation("pr-1", tuesday, 0.0, false),
                    Map.of("key", "pr-2", "at", "not-a-timestamp", "value", 3.0),
                    Map.of("at", tuesday.toString(), "value", 3.0)))));

        CycleTimeResponse response = cycleTimeService.getCycleTime(
            manager, null, null, MetricGranularity.DAY, WEEK_START, WEEK_END);

        assertThat(response.pullRequestCount()).isOne();
        assertThat(response.unreviewedPullRequestCount()).isOne();
        assertThat(response.stages().get(0).sampleSize()).isOne();
        assertThat(response.bottleneck()).isNull();
    }

    @Test
    void doraSeriesRejectsCycleTimeStageTypes() {
        assertThatThrownBy(() -> metricService.getSeries(
            manager, null, null, MetricType.PR_PICKUP_TIME_HOURS, MetricGranularity.WEEK, WEEK_START, WEEK_END))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                assertThat(error.safeDetail()).contains("/api/v1/metrics/cycle-time");
            });
    }

    private MetricSnapshot snapshot(
            MetricType type,
            MetricGranularity granularity,
            Instant start,
            Instant end,
            List<Map<String, Object>> observations) {
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setRepository(repository);
        snapshot.setMetricType(type);
        snapshot.setGranularity(granularity);
        snapshot.setPeriodStart(start);
        snapshot.setPeriodEnd(end);
        snapshot.setValue(BigDecimal.ZERO);
        snapshot.setUnit("hours");
        snapshot.setSampleSize(observations.size());
        snapshot.setCalculationVersion(CycleTimeService.CALCULATION_VERSION);
        snapshot.setCalculatedAt(Instant.now());
        snapshot.setDimensions(Map.of("observations", observations));
        return snapshot;
    }

    private static Map<String, Object> observation(String key, Instant at, double hours, boolean reviewed) {
        // The engine writes Python isoformat timestamps with an explicit offset.
        return Map.of(
            "key", key,
            "at", at.toString().replace("Z", "+00:00"),
            "value", hours,
            "reviewed", reviewed
        );
    }
}
