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

import com.adept.api.common.domain.DeploymentStatus;
import com.adept.api.common.domain.IncidentSeverity;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ApiException;
import com.adept.api.deployment.ChangeFailureRateRow;
import com.adept.api.deployment.DeploymentRepository;
import com.adept.api.incident.IncidentRepository;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.metric.dto.ChangeFailureRateDetailDto;
import com.adept.api.metric.dto.ChangeFailureRateDetailsResponse;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetricService#getChangeFailureRateDetails}.
 */
@ExtendWith(MockitoExtension.class)
class MetricServiceChangeFailureRateTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-09-08T00:00:00Z");

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

    private ChangeFailureRateRow mockRow(String status, String commitSha, UUID incidentId, String severity) {
        ChangeFailureRateRow row = mock(ChangeFailureRateRow.class);
        lenient().when(row.getDeploymentId()).thenReturn(UUID.randomUUID());
        lenient().when(row.getRepositoryId()).thenReturn(repositoryId);
        lenient().when(row.getRepositoryName()).thenReturn("engine");
        lenient().when(row.getRepositoryFullName()).thenReturn("acme/engine");
        lenient().when(row.getEnvironment()).thenReturn("production");
        lenient().when(row.getStatus()).thenReturn(status);
        lenient().when(row.getCommitSha()).thenReturn(commitSha);
        lenient().when(row.getFinishedAt()).thenReturn(Instant.parse("2026-09-02T10:00:00Z"));
        lenient().when(row.getIncidentId()).thenReturn(incidentId);
        lenient().when(row.getIncidentTitle()).thenReturn(incidentId == null ? null : "Checkout 500s");
        lenient().when(row.getIncidentSeverity()).thenReturn(severity);
        return row;
    }

    private void givenRows(List<ChangeFailureRateRow> rows, long failed) {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(deploymentRepository.findChangeFailureRateRows(
            eq(List.of(repositoryId)), any(Instant.class), any(Instant.class), any(Pageable.class)
        )).thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), rows.size()));
        when(deploymentRepository.countFailedProductionDeployments(List.of(repositoryId), FROM, TO))
            .thenReturn(failed);
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void flagsFailuresByStatusOrLinkedIncidentLikeTheEngine() {
        UUID incidentId = UUID.randomUUID();
        ChangeFailureRateRow success   = mockRow("SUCCESS", "good001", null, null);
        ChangeFailureRateRow failure   = mockRow("FAILURE", "bad0002", null, null);
        ChangeFailureRateRow cancelled = mockRow("CANCELLED", "cncl003", null, null);
        ChangeFailureRateRow linked    = mockRow("SUCCESS", "inc0004", incidentId, "SEV1");
        givenRows(List.of(linked, cancelled, failure, success), 2);

        ChangeFailureRateDetailsResponse response = metricService.getChangeFailureRateDetails(
            managerPrincipal, null, null, FROM, TO, 0, 20);

        assertThat(response.timezone()).isEqualTo("Asia/Colombo");
        assertThat(response.repositoryCount()).isEqualTo(1);
        assertThat(response.items()).extracting(ChangeFailureRateDetailDto::isFailure)
            .containsExactly(true, false, true, false);

        ChangeFailureRateDetailDto linkedItem = response.items().get(0);
        assertThat(linkedItem.status()).isEqualTo(DeploymentStatus.SUCCESS);
        assertThat(linkedItem.commitSha()).isEqualTo("inc0004");
        assertThat(linkedItem.environment()).isEqualTo("production");
        assertThat(linkedItem.incident()).isNotNull();
        assertThat(linkedItem.incident().id()).isEqualTo(incidentId);
        assertThat(linkedItem.incident().title()).isEqualTo("Checkout 500s");
        assertThat(linkedItem.incident().severity()).isEqualTo(IncidentSeverity.SEV1);

        assertThat(response.items().get(1).status()).isEqualTo(DeploymentStatus.CANCELLED);
        assertThat(response.items().get(2).status()).isEqualTo(DeploymentStatus.FAILURE);
        assertThat(response.items().get(2).incident()).isNull();
    }

    @Test
    void totalsMatchTheEngineNumeratorAndDenominator() {
        givenRows(List.of(
            mockRow("FAILURE", "bad0001", null, null),
            mockRow("SUCCESS", "good002", null, null),
            mockRow("SUCCESS", "good003", null, null)
        ), 1);

        ChangeFailureRateDetailsResponse response = metricService.getChangeFailureRateDetails(
            managerPrincipal, null, null, FROM, TO, 0, 20);

        // total_deployments = 3, failed_deployments = 1, value = round(1 / 3 * 100, 2).
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalDeployments()).isEqualTo(3);
        assertThat(response.failedDeployments()).isEqualTo(1);
        assertThat(response.failureRatePercent()).isEqualTo(33.33);
    }

    @Test
    void skipsFailureCountWhenWindowHasNoDeployments() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(deploymentRepository.findChangeFailureRateRows(any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        ChangeFailureRateDetailsResponse response = metricService.getChangeFailureRateDetails(
            managerPrincipal, null, null, FROM, TO, 0, 20);

        assertThat(response.totalDeployments()).isZero();
        assertThat(response.failedDeployments()).isZero();
        assertThat(response.failureRatePercent()).isZero();
        verify(deploymentRepository, never()).countFailedProductionDeployments(any(), any(), any());
    }

    @Test
    void mapsMissingIncidentSeverityToUnknown() {
        givenRows(List.of(mockRow("SUCCESS", "inc0001", UUID.randomUUID(), null)), 1);

        ChangeFailureRateDetailsResponse response = metricService.getChangeFailureRateDetails(
            managerPrincipal, null, null, FROM, TO, 0, 20);

        assertThat(response.items().get(0).incident().severity()).isEqualTo(IncidentSeverity.UNKNOWN);
        assertThat(response.items().get(0).isFailure()).isTrue();
    }

    @Test
    void passesHalfOpenWindowAndPageToRepository() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(deploymentRepository.findChangeFailureRateRows(any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));

        metricService.getChangeFailureRateDetails(managerPrincipal, null, null, FROM, TO, 2, 10);

        verify(deploymentRepository).findChangeFailureRateRows(List.of(repositoryId), FROM, TO, PageRequest.of(2, 10));
    }

    @Test
    void returnsEmptyResponseWhenNoRepositoriesAreAccessible() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());

        ChangeFailureRateDetailsResponse response = metricService.getChangeFailureRateDetails(
            managerPrincipal, null, null, FROM, TO, 0, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalDeployments()).isZero();
        assertThat(response.failedDeployments()).isZero();
        assertThat(response.repositoryCount()).isZero();
        verifyNoInteractions(deploymentRepository);
    }

    @Test
    void rejectsInvertedRange() {
        assertThatThrownBy(() -> metricService.getChangeFailureRateDetails(
            managerPrincipal, null, null, TO, FROM, 0, 20
        )).isInstanceOf(ApiException.class);
        verifyNoInteractions(deploymentRepository);
    }
}
