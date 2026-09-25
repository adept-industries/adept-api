package com.adept.api.metric;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.adept.api.common.domain.IncidentSeverity;
import com.adept.api.common.domain.IncidentSource;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ApiException;
import com.adept.api.deployment.DeploymentRepository;
import com.adept.api.incident.IncidentRepository;
import com.adept.api.incident.RecoveryTimeRow;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.metric.dto.RecoveryTimeDetailDto;
import com.adept.api.metric.dto.RecoveryTimeDetailsResponse;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.pullrequest.PullRequestRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.RepositoryScopeService;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetricService#getRecoveryTimeDetails}.
 */
@ExtendWith(MockitoExtension.class)
class MetricServiceRecoveryTimeTest {

    @Mock private MetricSnapshotRepository metricSnapshotRepository;
    @Mock private GitRepositoryRepository  gitRepositoryRepository;
    @Mock private ProjectRepository        projectRepository;
    @Mock private ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    @Mock private RepositoryScopeService   repositoryScopeService;
    @Mock private WorkspaceRepository      workspaceRepository;
    @Mock private DeploymentRepository     deploymentRepository;
    @Mock private PullRequestRepository    pullRequestRepository;
    @Mock private IncidentRepository       incidentRepository;

    @InjectMocks
    private MetricService metricService;

    private UUID workspaceId;
    private UUID repositoryId;
    private AuthenticatedPrincipal managerPrincipal;
    private GitRepository repository;

    @BeforeEach
    void setUp() {
        workspaceId  = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        managerPrincipal = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            UUID.randomUUID(),
            workspaceId,
            MembershipRole.MANAGER,
            1
        );

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setTimezone("Asia/Colombo");

        repository = new GitRepository();
        repository.setId(repositoryId);
        repository.setWorkspace(workspace);
        repository.setTrackingEnabled(true);
        repository.setArchived(false);
        repository.setName("engine");
        repository.setFullName("acme/engine");

        lenient().when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private RecoveryTimeRow mockRow(
            String source,
            String severity,
            Instant detected,
            Instant resolved,
            Instant effectiveResolved,
            UUID failedDeploymentId,
            UUID recoveryDeploymentId) {
        RecoveryTimeRow row = mock(RecoveryTimeRow.class);
        lenient().when(row.getIncidentId()).thenReturn(UUID.randomUUID());
        lenient().when(row.getTitle()).thenReturn("Checkout 500s");
        lenient().when(row.getSource()).thenReturn(source);
        lenient().when(row.getSeverity()).thenReturn(severity);
        lenient().when(row.getRepositoryId()).thenReturn(repositoryId);
        lenient().when(row.getRepositoryName()).thenReturn("engine");
        lenient().when(row.getRepositoryFullName()).thenReturn("acme/engine");
        lenient().when(row.getDetectedAt()).thenReturn(detected);
        lenient().when(row.getResolvedAt()).thenReturn(resolved);
        lenient().when(row.getEffectiveResolvedAt()).thenReturn(effectiveResolved);
        lenient().when(row.getFailedDeploymentId()).thenReturn(failedDeploymentId);
        lenient().when(row.getFailedDeploymentCommitSha()).thenReturn("bad0001");
        lenient().when(row.getFailedDeploymentEnvironment()).thenReturn("production");
        lenient().when(row.getFailedDeploymentFinishedAt()).thenReturn(detected);
        lenient().when(row.getRecoveryDeploymentId()).thenReturn(recoveryDeploymentId);
        lenient().when(row.getRecoveryDeploymentCommitSha()).thenReturn("good002");
        lenient().when(row.getRecoveryDeploymentEnvironment()).thenReturn("production");
        lenient().when(row.getRecoveryDeploymentFinishedAt()).thenReturn(effectiveResolved);
        return row;
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void mapsResolvedIncidentWithCorrelatedDeployments() {
        Instant detected  = Instant.parse("2026-09-02T10:00:00Z");
        Instant resolved  = Instant.parse("2026-09-02T13:00:00Z");
        Instant recovered = Instant.parse("2026-09-02T12:30:00Z");
        UUID failedId   = UUID.randomUUID();
        UUID recoveryId = UUID.randomUUID();

        RecoveryTimeRow row = mockRow("GITHUB", "SEV1", detected, resolved, recovered, failedId, recoveryId);
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(incidentRepository.findRecoveryTimeRows(
            eq(List.of(repositoryId)), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        RecoveryTimeDetailsResponse response = metricService.getRecoveryTimeDetails(
            managerPrincipal, null, null,
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-09-08T00:00:00Z"),
            0, 20
        );

        assertThat(response.timezone()).isEqualTo("Asia/Colombo");
        assertThat(response.repositoryCount()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);

        RecoveryTimeDetailDto item = response.items().get(0);
        assertThat(item.title()).isEqualTo("Checkout 500s");
        assertThat(item.source()).isEqualTo(IncidentSource.GITHUB);
        assertThat(item.severity()).isEqualTo(IncidentSeverity.SEV1);
        assertThat(item.detectedAt()).isEqualTo(detected);
        // Recovery deployment finish time is the metric's resolution instant.
        assertThat(item.resolvedAt()).isEqualTo(recovered);
        assertThat(item.recoveryDurationSeconds()).isEqualTo(2L * 3600 + 30 * 60);

        assertThat(item.failedDeployment()).isNotNull();
        assertThat(item.failedDeployment().id()).isEqualTo(failedId);
        assertThat(item.failedDeployment().commitSha()).isEqualTo("bad0001");
        assertThat(item.recoveryDeployment()).isNotNull();
        assertThat(item.recoveryDeployment().id()).isEqualTo(recoveryId);
        assertThat(item.recoveryDeployment().finishedAt()).isEqualTo(recovered);
    }

    @Test
    void leavesDeploymentsNullWhenIncidentIsNotCorrelated() {
        Instant detected = Instant.parse("2026-09-05T08:00:00Z");
        Instant resolved = Instant.parse("2026-09-05T08:45:00Z");

        RecoveryTimeRow row = mockRow("MANUAL", "UNKNOWN", detected, resolved, resolved, null, null);
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(incidentRepository.findRecoveryTimeRows(any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(row)));

        RecoveryTimeDetailsResponse response = metricService.getRecoveryTimeDetails(
            managerPrincipal, null, null,
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-09-08T00:00:00Z"),
            0, 20
        );

        RecoveryTimeDetailDto item = response.items().get(0);
        assertThat(item.source()).isEqualTo(IncidentSource.MANUAL);
        assertThat(item.severity()).isEqualTo(IncidentSeverity.UNKNOWN);
        assertThat(item.recoveryDurationSeconds()).isEqualTo(45L * 60);
        assertThat(item.failedDeployment()).isNull();
        assertThat(item.recoveryDeployment()).isNull();
    }

    @Test
    void passesHalfOpenWindowAndPageToRepository() {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to   = Instant.parse("2026-09-08T00:00:00Z");
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(incidentRepository.findRecoveryTimeRows(any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));

        metricService.getRecoveryTimeDetails(managerPrincipal, null, null, from, to, 2, 10);

        verify(incidentRepository).findRecoveryTimeRows(List.of(repositoryId), from, to, PageRequest.of(2, 10));
    }

    @Test
    void returnsEmptyResponseWhenNoRepositoriesAreAccessible() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());

        RecoveryTimeDetailsResponse response = metricService.getRecoveryTimeDetails(
            managerPrincipal, null, null,
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-09-08T00:00:00Z"),
            0, 20
        );

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.repositoryCount()).isZero();
        verifyNoInteractions(incidentRepository);
    }

    @Test
    void rejectsInvertedRange() {
        assertThatThrownBy(() -> metricService.getRecoveryTimeDetails(
            managerPrincipal, null, null,
            Instant.parse("2026-09-08T00:00:00Z"),
            Instant.parse("2026-09-01T00:00:00Z"),
            0, 20
        )).isInstanceOf(ApiException.class);
        verifyNoInteractions(incidentRepository);
    }
}
