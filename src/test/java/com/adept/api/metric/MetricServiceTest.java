package com.adept.api.metric;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.adept.api.common.domain.DeploymentSource;
import com.adept.api.common.domain.DeploymentStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ApiException;
import com.adept.api.deployment.Deployment;
import com.adept.api.deployment.DeploymentRepository;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.metric.dto.DeploymentFrequencyDetailsResponse;
import com.adept.api.metric.dto.DoraMetricsSeriesResponse;
import com.adept.api.metric.dto.DoraMetricsSummaryResponse;
import com.adept.api.project.Project;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLink;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.RepositoryScopeService;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class MetricServiceTest {

    @Mock
    private MetricSnapshotRepository metricSnapshotRepository;

    @Mock
    private GitRepositoryRepository gitRepositoryRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectRepositoryLinkRepository projectRepositoryLinkRepository;

    @Mock
    private RepositoryScopeService repositoryScopeService;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private DeploymentRepository deploymentRepository;

    @InjectMocks
    private MetricService metricService;

    private UUID workspaceId;
    private UUID membershipId;
    private UUID repositoryId;
    private AuthenticatedPrincipal managerPrincipal;
    private AuthenticatedPrincipal leadPrincipal;
    private GitRepository repository;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        membershipId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        managerPrincipal = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            membershipId,
            workspaceId,
            MembershipRole.MANAGER,
            1
        );
        leadPrincipal = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            membershipId,
            workspaceId,
            MembershipRole.LEAD,
            1
        );

        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setTimezone("UTC");

        repository = new GitRepository();
        repository.setId(repositoryId);
        repository.setWorkspace(workspace);
        repository.setTrackingEnabled(true);
        repository.setArchived(false);
        repository.setName("core");
        repository.setFullName("acme/core");

        lenient().when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
    }

    @Test
    void managerCanQueryAllAccessibleRepositoriesByDefault() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(metricSnapshotRepository.findSnapshots(
            eq(workspaceId),
            eq(List.of(repositoryId)),
            eq(MetricGranularity.DAY),
            eq(MetricService.CALCULATION_VERSION),
            any(),
            any()
        )).thenReturn(List.of());

        DoraMetricsSummaryResponse response = metricService.getSummary(
            managerPrincipal, null, null, null, null
        );

        assertThat(response.repositoryCount()).isEqualTo(1);
        assertThat(response.timezone()).isEqualTo("UTC");
    }

    @Test
    void leadIsRestrictedToAssignedRepositories() {
        when(gitRepositoryRepository.findAllLeadReadableRepositories(workspaceId, membershipId))
            .thenReturn(List.of());

        DoraMetricsSummaryResponse response = metricService.getSummary(
            leadPrincipal, null, null, null, null
        );

        assertThat(response.repositoryCount()).isZero();
    }

    @Test
    void aggregatesDeploymentFrequencyFromSnapshots() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-15T00:00:00Z");

        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setRepository(repository);
        snapshot.setMetricType(MetricType.DEPLOYMENT_FREQUENCY);
        snapshot.setGranularity(MetricGranularity.DAY);
        snapshot.setPeriodStart(from);
        snapshot.setPeriodEnd(to);
        snapshot.setValue(new BigDecimal("2.00"));
        snapshot.setUnit("deployments/week");
        snapshot.setSampleSize(4);
        snapshot.setCalculatedAt(to);
        snapshot.setDimensions(Map.of(
            "observations",
            List.of(
                observation("d1", from.plusSeconds(3600), 1.0),
                observation("d2", from.plusSeconds(7200), 1.0),
                observation("d3", from.plusSeconds(10800), 1.0),
                observation("d4", from.plusSeconds(14400), 1.0)
            )
        ));

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(metricSnapshotRepository.findSnapshots(
            workspaceId,
            List.of(repositoryId),
            MetricGranularity.DAY,
            MetricService.CALCULATION_VERSION,
            from,
            to
        )).thenReturn(List.of(snapshot));

        DoraMetricsSummaryResponse response = metricService.getSummary(
            managerPrincipal, null, null, from, to
        );

        assertThat(response.deploymentFrequency().sampleSize()).isEqualTo(4);
        assertThat(response.deploymentFrequency().value()).isEqualByComparingTo("2.00");
    }

    @Test
    void aggregatesChangeLeadTimeMedianFromSnapshots() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-15T00:00:00Z");

        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setRepository(repository);
        snapshot.setMetricType(MetricType.CHANGE_LEAD_TIME_HOURS);
        snapshot.setGranularity(MetricGranularity.DAY);
        snapshot.setPeriodStart(from);
        snapshot.setPeriodEnd(to);
        snapshot.setValue(new BigDecimal("12.00"));
        snapshot.setUnit("hours");
        snapshot.setSampleSize(3);
        snapshot.setCalculatedAt(to);
        snapshot.setDimensions(Map.of(
            "observations",
            List.of(
                observation("pr-1", from.plusSeconds(100), 6.0),
                observation("pr-2", from.plusSeconds(200), 12.0),
                observation("pr-3", from.plusSeconds(300), 18.0)
            )
        ));

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(metricSnapshotRepository.findSnapshots(
            workspaceId,
            List.of(repositoryId),
            MetricGranularity.DAY,
            MetricService.CALCULATION_VERSION,
            from,
            to
        )).thenReturn(List.of(snapshot));

        DoraMetricsSummaryResponse response = metricService.getSummary(
            managerPrincipal, null, null, from, to
        );

        assertThat(response.changeLeadTime().sampleSize()).isEqualTo(3);
        assertThat(response.changeLeadTime().value()).isEqualByComparingTo("12.00");
        assertThat(response.changeLeadTime().dimensions()).containsEntry("mean", 12.0);
        assertThat(response.changeLeadTime().dimensions()).containsEntry("p50", 12.0);
        assertThat(response.changeLeadTime().dimensions()).containsEntry("p75", 15.0);
        assertThat(response.changeLeadTime().dimensions()).containsEntry("p90", 16.8);
    }

    @Test
    void aggregatesRecoveryTimeMedianFromSnapshots() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-15T00:00:00Z");

        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setRepository(repository);
        snapshot.setMetricType(MetricType.FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS);
        snapshot.setGranularity(MetricGranularity.DAY);
        snapshot.setPeriodStart(from);
        snapshot.setPeriodEnd(to);
        snapshot.setValue(new BigDecimal("2.50"));
        snapshot.setUnit("hours");
        snapshot.setSampleSize(2);
        snapshot.setCalculatedAt(to);
        snapshot.setDimensions(Map.of(
            "observations",
            List.of(
                observation("inc-1", from.plusSeconds(100), 2.0),
                observation("inc-2", from.plusSeconds(200), 3.0)
            )
        ));

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(metricSnapshotRepository.findSnapshots(
            workspaceId,
            List.of(repositoryId),
            MetricGranularity.DAY,
            MetricService.CALCULATION_VERSION,
            from,
            to
        )).thenReturn(List.of(snapshot));

        DoraMetricsSummaryResponse response = metricService.getSummary(
            managerPrincipal, null, null, from, to
        );

        assertThat(response.recoveryTime().sampleSize()).isEqualTo(2);
        assertThat(response.recoveryTime().value()).isEqualByComparingTo("2.50");
        assertThat(response.recoveryTime().dimensions()).containsEntry("mean", 2.5);
        assertThat(response.recoveryTime().dimensions()).containsEntry("p50", 2.5);
    }

    @Test
    void aggregatesChangeFailureRateFromSnapshots() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-15T00:00:00Z");

        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setRepository(repository);
        snapshot.setMetricType(MetricType.CHANGE_FAILURE_RATE_PERCENT);
        snapshot.setGranularity(MetricGranularity.DAY);
        snapshot.setPeriodStart(from);
        snapshot.setPeriodEnd(to);
        snapshot.setValue(new BigDecimal("25.00"));
        snapshot.setUnit("percent");
        snapshot.setSampleSize(4);
        snapshot.setCalculatedAt(to);
        snapshot.setDimensions(Map.of(
            "observations",
            List.of(
                observation("dep-1", from.plusSeconds(100), 0.0),
                observation("dep-2", from.plusSeconds(200), 1.0),
                observation("dep-3", from.plusSeconds(300), 0.0),
                observation("dep-4", from.plusSeconds(400), 0.0)
            )
        ));

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(metricSnapshotRepository.findSnapshots(
            workspaceId,
            List.of(repositoryId),
            MetricGranularity.DAY,
            MetricService.CALCULATION_VERSION,
            from,
            to
        )).thenReturn(List.of(snapshot));

        DoraMetricsSummaryResponse response = metricService.getSummary(
            managerPrincipal, null, null, from, to
        );

        assertThat(response.changeFailureRate().sampleSize()).isEqualTo(4);
        assertThat(response.changeFailureRate().value()).isEqualByComparingTo("25.00");
        assertThat(response.changeFailureRate().dimensions()).containsEntry("total_deployments", 4);
        assertThat(response.changeFailureRate().dimensions()).containsEntry("failed_deployments", 1L);
    }

    @Test
    void seriesReturnsBucketedPoints() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant mid = Instant.parse("2026-08-02T00:00:00Z");
        Instant to = Instant.parse("2026-08-03T00:00:00Z");

        MetricSnapshot snapshot1 = new MetricSnapshot();
        snapshot1.setRepository(repository);
        snapshot1.setMetricType(MetricType.DEPLOYMENT_FREQUENCY);
        snapshot1.setGranularity(MetricGranularity.DAY);
        snapshot1.setPeriodStart(from);
        snapshot1.setPeriodEnd(mid);
        snapshot1.setValue(new BigDecimal("1.00"));
        snapshot1.setUnit("deployments");
        snapshot1.setSampleSize(1);
        snapshot1.setDimensions(Map.of(
            "observations",
            List.of(observation("d1", from.plusSeconds(100), 1.0))
        ));

        MetricSnapshot snapshot2 = new MetricSnapshot();
        snapshot2.setRepository(repository);
        snapshot2.setMetricType(MetricType.DEPLOYMENT_FREQUENCY);
        snapshot2.setGranularity(MetricGranularity.DAY);
        snapshot2.setPeriodStart(mid);
        snapshot2.setPeriodEnd(to);
        snapshot2.setValue(new BigDecimal("2.00"));
        snapshot2.setUnit("deployments");
        snapshot2.setSampleSize(2);
        snapshot2.setDimensions(Map.of(
            "observations",
            List.of(
                observation("d2", mid.plusSeconds(100), 1.0),
                observation("d3", mid.plusSeconds(200), 1.0)
            )
        ));

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(metricSnapshotRepository.findSnapshotsByMetricType(
            workspaceId,
            List.of(repositoryId),
            MetricType.DEPLOYMENT_FREQUENCY,
            MetricGranularity.DAY,
            MetricService.CALCULATION_VERSION,
            from,
            to
        )).thenReturn(List.of(snapshot1, snapshot2));

        DoraMetricsSeriesResponse series = metricService.getSeries(
            managerPrincipal,
            null,
            null,
            MetricType.DEPLOYMENT_FREQUENCY,
            MetricGranularity.DAY,
            from,
            to
        );

        assertThat(series.series()).hasSize(2);
        assertThat(series.series().get(0).value()).isEqualByComparingTo("1.00");
        assertThat(series.series().get(1).value()).isEqualByComparingTo("2.00");
        assertThat(series.series().get(0).sampleSize()).isEqualTo(1);
        assertThat(series.series().get(1).sampleSize()).isEqualTo(2);
    }

    @Test
    void seriesPercentilesAreExactForSmallSamples() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-02T00:00:00Z");

        MetricSnapshot single = durationSnapshot(
            repository,
            from,
            to,
            List.of(observation("k1", from.plusSeconds(10), 42.0))
        );
        MetricSnapshot two = durationSnapshot(
            repository,
            from,
            to,
            List.of(
                observation("k1", from.plusSeconds(10), 10.0),
                observation("k2", from.plusSeconds(20), 20.0)
            )
        );

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(metricSnapshotRepository.findSnapshotsByMetricType(
            workspaceId,
            List.of(repositoryId),
            MetricType.CHANGE_LEAD_TIME_HOURS,
            MetricGranularity.DAY,
            MetricService.CALCULATION_VERSION,
            from,
            to
        )).thenReturn(List.of(single), List.of(two));

        DoraMetricsSeriesResponse singleResponse = metricService.getSeries(
            managerPrincipal,
            null,
            null,
            MetricType.CHANGE_LEAD_TIME_HOURS,
            MetricGranularity.DAY,
            from,
            to
        );
        DoraMetricsSeriesResponse twoResponse = metricService.getSeries(
            managerPrincipal,
            null,
            null,
            MetricType.CHANGE_LEAD_TIME_HOURS,
            MetricGranularity.DAY,
            from,
            to
        );

        assertThat(singleResponse.series().getFirst().value()).isEqualByComparingTo("42.00");
        assertThat(twoResponse.series().getFirst().value()).isEqualByComparingTo("15.00");
    }

    @Test
    void seriesDedupesSharedObservationsAcrossSnapshots() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-02T00:00:00Z");

        MetricSnapshot first = durationSnapshot(
            repository,
            from,
            to,
            List.of(
                observation("shared", from.plusSeconds(10), 100.0),
                observation("first-only", from.plusSeconds(20), 50.0)
            )
        );
        MetricSnapshot second = durationSnapshot(
            repository,
            from,
            to,
            List.of(
                observation("shared", from.plusSeconds(10), 100.0),
                observation("second-only", from.plusSeconds(30), 150.0)
            )
        );

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(metricSnapshotRepository.findSnapshots(
            workspaceId,
            List.of(repositoryId),
            MetricGranularity.DAY,
            MetricService.CALCULATION_VERSION,
            from,
            to
        )).thenReturn(List.of(first, second));

        DoraMetricsSummaryResponse response = metricService.getSummary(
            managerPrincipal,
            null,
            null,
            from,
            to
        );

        assertThat(response.changeLeadTime().value()).isEqualByComparingTo("100.00");
        assertThat(response.changeLeadTime().sampleSize()).isEqualTo(3);
    }

    @Test
    void selectsOneAuthorizedRepositoryWithinProjectScope() {
        UUID projectId = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);
        project.setWorkspace(workspace);
        ProjectRepositoryLink link = new ProjectRepositoryLink();
        link.setProject(project);
        link.setRepository(repository);

        when(projectRepository.findByIdAndWorkspaceId(projectId, workspaceId))
            .thenReturn(Optional.of(project));
        when(projectRepositoryLinkRepository.findAllWithRepositoryByProjectId(projectId))
            .thenReturn(List.of(link));
        when(projectRepositoryLinkRepository.findAllReadableByLead(projectId, membershipId))
            .thenReturn(List.of(link));
        when(metricSnapshotRepository.findSnapshots(
            eq(workspaceId),
            eq(List.of(repositoryId)),
            eq(MetricGranularity.DAY),
            eq(MetricService.CALCULATION_VERSION),
            any(),
            any()
        )).thenReturn(List.of());

        DoraMetricsSummaryResponse manager = metricService.getSummary(
            managerPrincipal, projectId, repositoryId, null, null
        );
        DoraMetricsSummaryResponse lead = metricService.getSummary(
            leadPrincipal, projectId, repositoryId, null, null
        );

        assertThat(manager.projectId()).isEqualTo(projectId);
        assertThat(manager.repositoryId()).isEqualTo(repositoryId);
        assertThat(manager.repositoryCount()).isEqualTo(1);
        assertThat(lead.projectId()).isEqualTo(projectId);
        assertThat(lead.repositoryId()).isEqualTo(repositoryId);
        assertThat(lead.repositoryCount()).isEqualTo(1);
    }

    @Test
    void rejectsRepositoryOutsideAuthorizedProjectScope() {
        UUID projectId = UUID.randomUUID();
        UUID unavailableRepositoryId = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);
        project.setWorkspace(workspace);

        when(projectRepository.findByIdAndWorkspaceId(projectId, workspaceId))
            .thenReturn(Optional.of(project));
        when(projectRepositoryLinkRepository.findAllReadableByLead(projectId, membershipId))
            .thenReturn(List.of());

        assertThatThrownBy(() -> metricService.getSummary(
            leadPrincipal,
            projectId,
            unavailableRepositoryId,
            null,
            null
        )).isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectsInvalidRange() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        assertThatThrownBy(() -> metricService.getSummary(
            managerPrincipal,
            null,
            null,
            now,
            now
        )).isInstanceOf(ApiException.class);
    }

    @Test
    void pooledFreshnessUsesOldestRepositoryAndRequiresEveryRepository() {
        UUID secondRepositoryId = UUID.randomUUID();
        GitRepository secondRepository = new GitRepository();
        secondRepository.setId(secondRepositoryId);
        secondRepository.setWorkspace(workspace);
        secondRepository.setTrackingEnabled(true);
        secondRepository.setArchived(false);
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId))
            .thenReturn(List.of(repository, secondRepository));

        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-02T00:00:00Z");
        Instant oldest = Instant.parse("2026-08-02T00:05:00Z");
        MetricSnapshot first = durationSnapshot(
            repository,
            from,
            to,
            List.of(observation("first", from.plusSeconds(1), 1.0))
        );
        first.setCalculatedAt(oldest);
        MetricSnapshot second = durationSnapshot(
            secondRepository,
            from,
            to,
            List.of(observation("second", from.plusSeconds(2), 2.0))
        );
        second.setCalculatedAt(oldest.plusSeconds(300));

        when(metricSnapshotRepository.findSnapshots(
            workspaceId,
            List.of(repositoryId, secondRepositoryId),
            MetricGranularity.DAY,
            MetricService.CALCULATION_VERSION,
            from,
            to
        )).thenReturn(List.of(first, second), List.of(first));

        DoraMetricsSummaryResponse complete = metricService.getSummary(
            managerPrincipal, null, null, from, to
        );
        DoraMetricsSummaryResponse incomplete = metricService.getSummary(
            managerPrincipal, null, null, from, to
        );

        assertThat(complete.calculatedAt()).isEqualTo(oldest);
        assertThat(incomplete.calculatedAt()).isNull();
        assertThat(incomplete.stale()).isTrue();
    }

    @Test
    void explicitRepositoryScopeRejectsUntrackedRepositories() {
        repository.setTrackingEnabled(false);
        when(repositoryScopeService.requireReadableRepository(managerPrincipal, repositoryId))
            .thenReturn(repository);

        assertThatThrownBy(() -> metricService.getSummary(
            managerPrincipal,
            null,
            repositoryId,
            null,
            null
        )).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getDeploymentFrequencyDetailsReturnsPaginatedDeployments() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-15T00:00:00Z");

        Deployment deployment = new Deployment();
        deployment.setId(UUID.randomUUID());
        deployment.setRepository(repository);
        deployment.setEnvironment("production");
        deployment.setSource(DeploymentSource.GITHUB_DEPLOYMENT);
        deployment.setStatus(DeploymentStatus.SUCCESS);
        deployment.setCommitSha("abcdef1234567890");
        deployment.setStartedAt(from.plusSeconds(100));
        deployment.setFinishedAt(from.plusSeconds(340));

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(deploymentRepository.findSuccessfulProductionDeployments(
            eq(List.of(repositoryId)),
            eq(from),
            eq(to),
            any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(deployment)));

        DeploymentFrequencyDetailsResponse response = metricService.getDeploymentFrequencyDetails(
            managerPrincipal,
            null,
            null,
            from,
            to,
            0,
            20
        );

        assertThat(response.repositoryCount()).isEqualTo(1);
        assertThat(response.timezone()).isEqualTo("UTC");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).commitSha()).isEqualTo("abcdef1234567890");
        assertThat(response.items().get(0).durationSeconds()).isEqualTo(240L);
        assertThat(response.items().get(0).repositoryName()).isEqualTo("core");
        assertThat(response.items().get(0).environment()).isEqualTo("production");
    }

    @Test
    void getDeploymentFrequencyDetailsReturnsEmptyWhenNoRepositoriesAccessible() {
        when(gitRepositoryRepository.findAllLeadReadableRepositories(workspaceId, membershipId))
            .thenReturn(List.of());

        DeploymentFrequencyDetailsResponse response = metricService.getDeploymentFrequencyDetails(
            leadPrincipal,
            null,
            null,
            null,
            null,
            0,
            20
        );

        assertThat(response.repositoryCount()).isZero();
        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    private MetricSnapshot durationSnapshot(
            GitRepository targetRepository,
            Instant from,
            Instant to,
            List<Map<String, Object>> observations) {
        MetricSnapshot snapshot = new MetricSnapshot();
        snapshot.setRepository(targetRepository);
        snapshot.setMetricType(MetricType.CHANGE_LEAD_TIME_HOURS);
        snapshot.setGranularity(MetricGranularity.DAY);
        snapshot.setPeriodStart(from);
        snapshot.setPeriodEnd(to);
        snapshot.setValue(BigDecimal.ZERO);
        snapshot.setUnit("hours");
        snapshot.setSampleSize(observations.size());
        snapshot.setCalculatedAt(to);
        snapshot.setDimensions(Map.of("observations", observations));
        return snapshot;
    }

    private static Map<String, Object> observation(String key, Instant at, double value) {
        return Map.of("key", key, "at", at.toString(), "value", value);
    }
}
