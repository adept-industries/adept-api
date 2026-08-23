package com.adept.api.metric;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.common.domain.MetricType;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.metric.dto.DoraMetricsSeriesResponse;
import com.adept.api.metric.dto.DoraMetricsSummaryResponse;
import com.adept.api.project.Project;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLink;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.RepositoryScopeService;
import com.adept.api.workspace.Workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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

        repository = new GitRepository();
        repository.setId(repositoryId);
        repository.setWorkspace(workspace);
        repository.setTrackingEnabled(true);
        repository.setArchived(false);
    }

    @Test
    void testGetSummaryReturnsEmptyWhenNoAccessibleRepositories() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());

        DoraMetricsSummaryResponse response = metricService.getSummary(
            managerPrincipal,
            null,
            null,
            null,
            null
        );

        assertThat(response.repositoryCount()).isEqualTo(0);
        assertThat(response.deploymentFrequency().value()).isEqualTo(BigDecimal.ZERO);
        assertThat(response.deploymentFrequency().rating()).isEqualTo(MetricRating.UNKNOWN);
        assertThat(response.changeLeadTime().value()).isEqualTo(BigDecimal.ZERO);
        assertThat(response.recoveryTime().value()).isEqualTo(BigDecimal.ZERO);
        assertThat(response.changeFailureRate().value()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void testGetSummaryAggregatesMetricsForManager() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));

        Instant now = Instant.now();
        Instant from = now.minus(30, ChronoUnit.DAYS);

        MetricSnapshot dfSnap = new MetricSnapshot();
        dfSnap.setMetricType(MetricType.DEPLOYMENT_FREQUENCY);
        dfSnap.setGranularity(MetricGranularity.DAY);
        dfSnap.setValue(BigDecimal.valueOf(14));
        dfSnap.setSampleSize(14);
        dfSnap.setUnit("deployments/day");
        dfSnap.setPeriodStart(from);
        dfSnap.setPeriodEnd(now);

        MetricSnapshot cltSnap = new MetricSnapshot();
        cltSnap.setMetricType(MetricType.CHANGE_LEAD_TIME_HOURS);
        cltSnap.setGranularity(MetricGranularity.DAY);
        cltSnap.setValue(BigDecimal.valueOf(4.5));
        cltSnap.setSampleSize(10);
        cltSnap.setUnit("hours");
        cltSnap.setPeriodStart(from);
        cltSnap.setPeriodEnd(now);

        MetricSnapshot recSnap = new MetricSnapshot();
        recSnap.setMetricType(MetricType.FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS);
        recSnap.setGranularity(MetricGranularity.DAY);
        recSnap.setValue(BigDecimal.valueOf(1.5));
        recSnap.setSampleSize(2);
        recSnap.setUnit("hours");
        recSnap.setPeriodStart(from);
        recSnap.setPeriodEnd(now);

        MetricSnapshot cfrSnap = new MetricSnapshot();
        cfrSnap.setMetricType(MetricType.CHANGE_FAILURE_RATE_PERCENT);
        cfrSnap.setGranularity(MetricGranularity.DAY);
        cfrSnap.setValue(BigDecimal.valueOf(7.14));
        cfrSnap.setSampleSize(14);
        cfrSnap.setUnit("percent");
        cfrSnap.setPeriodStart(from);
        cfrSnap.setPeriodEnd(now);

        when(metricSnapshotRepository.findSnapshots(
            eq(workspaceId),
            eq(List.of(repositoryId)),
            eq(MetricGranularity.DAY),
            any(),
            any()
        )).thenReturn(List.of(dfSnap, cltSnap, recSnap, cfrSnap));

        DoraMetricsSummaryResponse response = metricService.getSummary(
            managerPrincipal,
            null,
            null,
            from,
            now
        );

        assertThat(response.repositoryCount()).isEqualTo(1);
        assertThat(response.deploymentFrequency().sampleSize()).isEqualTo(14);
        assertThat(response.deploymentFrequency().rating()).isNotNull();
        assertThat(response.changeLeadTime().sampleSize()).isEqualTo(10);
        assertThat(response.changeLeadTime().value()).isEqualByComparingTo("4.50");
        assertThat(response.changeLeadTime().rating()).isEqualTo(MetricRating.HIGH);
        assertThat(response.recoveryTime().sampleSize()).isEqualTo(2);
        assertThat(response.recoveryTime().value()).isEqualByComparingTo("1.50");
        assertThat(response.changeFailureRate().sampleSize()).isEqualTo(14);
        assertThat(response.changeFailureRate().rating()).isEqualTo(MetricRating.HIGH);
    }

    @Test
    void testGetSummaryWithProjectFilterForLead() {
        UUID projectId = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);
        project.setWorkspace(workspace);

        when(projectRepository.findByIdAndWorkspaceId(projectId, workspaceId))
            .thenReturn(Optional.of(project));

        ProjectRepositoryLink link = new ProjectRepositoryLink();
        link.setProject(project);
        link.setRepository(repository);

        when(projectRepositoryLinkRepository.findAllReadableByLead(projectId, membershipId))
            .thenReturn(List.of(link));

        when(metricSnapshotRepository.findSnapshots(
            eq(workspaceId),
            eq(List.of(repositoryId)),
            eq(MetricGranularity.DAY),
            any(),
            any()
        )).thenReturn(List.of());

        DoraMetricsSummaryResponse response = metricService.getSummary(
            leadPrincipal,
            projectId,
            null,
            null,
            null
        );

        assertThat(response.projectId()).isEqualTo(projectId);
        assertThat(response.repositoryCount()).isEqualTo(1);
    }

    @Test
    void testGetSummaryProjectNotFoundThrows() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findByIdAndWorkspaceId(projectId, workspaceId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> metricService.getSummary(managerPrincipal, projectId, null, null, null))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void testGetSeriesReturnsAggregatedDataPoints() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));

        Instant t1 = Instant.parse("2026-08-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-08-02T00:00:00Z");

        MetricSnapshot s1 = new MetricSnapshot();
        s1.setMetricType(MetricType.DEPLOYMENT_FREQUENCY);
        s1.setGranularity(MetricGranularity.DAY);
        s1.setPeriodStart(t1);
        s1.setPeriodEnd(t2);
        s1.setValue(BigDecimal.valueOf(3));
        s1.setUnit("deployments/day");
        s1.setSampleSize(3);

        when(metricSnapshotRepository.findSnapshots(
            eq(workspaceId),
            eq(List.of(repositoryId)),
            eq(MetricGranularity.DAY),
            any(),
            any()
        )).thenReturn(List.of(s1));

        DoraMetricsSeriesResponse response = metricService.getSeries(
            managerPrincipal,
            null,
            null,
            null,
            MetricGranularity.DAY,
            t1,
            t2
        );

        assertThat(response.granularity()).isEqualTo(MetricGranularity.DAY);
        assertThat(response.series()).hasSize(1);
        assertThat(response.series().get(0).value()).isEqualByComparingTo("3.00");
        assertThat(response.series().get(0).sampleSize()).isEqualTo(3);
    }
}
