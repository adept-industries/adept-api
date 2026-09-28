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
import com.adept.api.metric.dto.CycleTimeResponse;
import com.adept.api.metric.dto.CycleTimeSizeBucketDto;
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

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        manager = new AuthenticatedPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), workspaceId, MembershipRole.MANAGER, 1);

        Workspace workspace = new Workspace();
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
            observation("pr-1", tuesday, 6.0, "M", 2),
            observation("pr-2", wednesday, 2.0, "S", 0))));
        days.add(snapshot(MetricType.PR_PICKUP_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-1", tuesday, 44.0, "M", 2),
            observation("pr-2", wednesday, 24.0, "S", 0))));
        // A second snapshot repeating pr-1 (for example a DAY bucket overlap) is counted once.
        days.add(snapshot(MetricType.PR_PICKUP_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-1", tuesday, 44.0, "M", 2))));
        days.add(snapshot(MetricType.PR_REVIEW_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-1", tuesday, 5.0, "M", 2))));
        // Merged outside the requested range.
        days.add(snapshot(MetricType.PR_DEPLOY_TIME_HOURS, MetricGranularity.DAY, WEEK_START, WEEK_END, List.of(
            observation("pr-9", WEEK_END.plusSeconds(60), 99.0, "XL", 0))));
        when(metricSnapshotRepository.findSnapshots(
            workspaceId, List.of(repositoryId), MetricGranularity.DAY,
            CycleTimeService.CALCULATION_VERSION, WEEK_START, WEEK_END)).thenReturn(days);
        when(metricSnapshotRepository.findSnapshots(
            workspaceId, List.of(repositoryId), MetricGranularity.WEEK,
            CycleTimeService.CALCULATION_VERSION, WEEK_START, WEEK_END)).thenReturn(List.of(
                snapshot(MetricType.PR_PICKUP_TIME_HOURS, MetricGranularity.WEEK, WEEK_START, WEEK_END, List.of(
                    observation("pr-1", tuesday, 44.0, "M", 2),
                    observation("pr-2", wednesday, 24.0, "S", 0)))));

        CycleTimeResponse response = cycleTimeService.getCycleTime(
            manager, null, null, null, WEEK_START, WEEK_END);

        assertThat(response.granularity()).isEqualTo(MetricGranularity.WEEK);
        assertThat(response.pullRequestCount()).isEqualTo(2);
        assertThat(response.bottleneck()).isEqualTo(CycleTimeStage.PICKUP);
        assertThat(response.stages()).extracting(CycleTimeStageDto::stage)
            .containsExactly(CycleTimeStage.values());
        CycleTimeStageDto pickup = response.stages().get(1);
        assertThat(pickup.medianHours()).isEqualByComparingTo("34.00");
        assertThat(pickup.sampleSize()).isEqualTo(2);
        assertThat(response.stages().get(4).sampleSize()).isZero();

        assertThat(response.series()).hasSize(1);
        assertThat(response.series().get(0).pullRequestCount()).isEqualTo(2);
        assertThat(response.series().get(0).stages().get(1).medianHours()).isEqualByComparingTo("34.00");

        assertThat(response.sizeBreakdown()).extracting(CycleTimeSizeBucketDto::size)
            .containsExactly("XS", "S", "M", "L", "XL");
        CycleTimeSizeBucketDto medium = response.sizeBreakdown().get(2);
        assertThat(medium.pullRequestCount()).isOne();
        assertThat(medium.pickupMedianHours()).isEqualByComparingTo("44.00");
        assertThat(medium.reviewMedianHours()).isEqualByComparingTo("5.00");
        assertThat(response.sizeBreakdown().get(1).reviewMedianHours()).isNull();

        assertThat(response.reviewRounds().reviewedPullRequestCount()).isEqualTo(2);
        assertThat(response.reviewRounds().averageRounds()).isEqualByComparingTo("1.00");
        assertThat(response.reviewRounds().pullRequestsWithChangesRequested()).isOne();
    }

    @Test
    void emptyScopeReturnsZeroStagesWithoutBottleneck() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());

        CycleTimeResponse response = cycleTimeService.getCycleTime(
            manager, null, null, MetricGranularity.DAY, WEEK_START, WEEK_END);

        assertThat(response.repositoryCount()).isZero();
        assertThat(response.bottleneck()).isNull();
        assertThat(response.stale()).isTrue();
        assertThat(response.stages()).hasSize(5).allMatch(stage -> stage.sampleSize() == 0);
        assertThat(response.series()).isEmpty();
        assertThat(response.reviewRounds().reviewedPullRequestCount()).isZero();
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

    private static Map<String, Object> observation(String key, Instant at, double hours, String size, int rounds) {
        // The engine writes Python isoformat timestamps with an explicit offset.
        return Map.of(
            "key", key,
            "at", at.toString().replace("Z", "+00:00"),
            "value", hours,
            "size", size,
            "rounds", rounds
        );
    }
}
