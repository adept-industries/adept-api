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
import org.springframework.data.domain.Pageable;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.deployment.DeploymentRepository;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.metric.dto.ChangeLeadTimeDetailsResponse;
import com.adept.api.project.ProjectRepository;
import com.adept.api.project.ProjectRepositoryLinkRepository;
import com.adept.api.pullrequest.ChangeLeadTimeRow;
import com.adept.api.pullrequest.PullRequestRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.RepositoryScopeService;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetricService#getChangeLeadTimeDetails}.
 */
@ExtendWith(MockitoExtension.class)
class MetricServiceChangeLeadTimeTest {

    @Mock private MetricSnapshotRepository metricSnapshotRepository;
    @Mock private GitRepositoryRepository  gitRepositoryRepository;
    @Mock private ProjectRepository        projectRepository;
    @Mock private ProjectRepositoryLinkRepository projectRepositoryLinkRepository;
    @Mock private RepositoryScopeService   repositoryScopeService;
    @Mock private WorkspaceRepository      workspaceRepository;
    @Mock private DeploymentRepository     deploymentRepository;
    @Mock private PullRequestRepository    pullRequestRepository;

    @InjectMocks
    private MetricService metricService;

    private UUID workspaceId;
    private UUID membershipId;
    private UUID repositoryId;
    private AuthenticatedPrincipal managerPrincipal;
    private GitRepository repository;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspaceId  = UUID.randomUUID();
        membershipId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        managerPrincipal = new AuthenticatedPrincipal(
            UUID.randomUUID(),
            membershipId,
            workspaceId,
            MembershipRole.MANAGER,
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
        repository.setName("engine");
        repository.setFullName("acme/engine");

        lenient().when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static ChangeLeadTimeRow mockRow(
            UUID prId,
            int number,
            String title,
            String author,
            UUID repoId,
            String repoName,
            String ownerLogin,
            String fullName,
            Instant firstCommit,
            Instant opened,
            Instant merged,
            Instant deployed,
            String environment,
            String commitSha) {

        ChangeLeadTimeRow row = mock(ChangeLeadTimeRow.class);
        when(row.getPrId()).thenReturn(prId);
        when(row.getPrNumber()).thenReturn(number);
        when(row.getPrTitle()).thenReturn(title);
        when(row.getAuthorLogin()).thenReturn(author);
        when(row.getRepositoryId()).thenReturn(repoId);
        when(row.getRepositoryName()).thenReturn(repoName);
        when(row.getRepositoryOwnerLogin()).thenReturn(ownerLogin);
        when(row.getRepositoryFullName()).thenReturn(fullName);
        when(row.getFirstCommitAt()).thenReturn(firstCommit);
        when(row.getOpenedAt()).thenReturn(opened);
        when(row.getMergedAt()).thenReturn(merged);
        when(row.getDeployedAt()).thenReturn(deployed);
        when(row.getDeploymentEnvironment()).thenReturn(environment);
        when(row.getDeploymentCommitSha()).thenReturn(commitSha);
        return row;
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void returnsPagedLeadTimeRowsWithCalculatedBreakdown() {
        Instant firstCommit = Instant.parse("2026-09-20T14:15:00Z");
        Instant opened      = Instant.parse("2026-09-20T16:00:00Z"); // +1h45m coding
        Instant merged      = Instant.parse("2026-09-21T09:30:00Z"); // +17h30m review
        Instant deployed    = Instant.parse("2026-09-21T09:42:00Z"); // +12m deploy
        // Total lead time = deployed - firstCommit = 70020s  (~19.45h)

        UUID prId = UUID.randomUUID();
        ChangeLeadTimeRow row = mockRow(
            prId, 142, "Add search index caching", "rangaNP",
            repositoryId, "engine", "acme", "acme/engine",
            firstCommit, opened, merged, deployed,
            "production", "abc1234"
        );

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(pullRequestRepository.findChangeLeadTimeRows(
            eq(List.of(repositoryId)),
            any(Instant.class),
            any(Instant.class),
            any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(row)));

        ChangeLeadTimeDetailsResponse response = metricService.getChangeLeadTimeDetails(
            managerPrincipal, null, null,
            firstCommit.minusSeconds(3600),
            deployed.plusSeconds(3600),
            0, 20
        );

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);

        var item = response.items().get(0);
        assertThat(item.prNumber()).isEqualTo(142);
        assertThat(item.prTitle()).isEqualTo("Add search index caching");
        assertThat(item.authorLogin()).isEqualTo("rangaNP");
        assertThat(item.repositoryFullName()).isEqualTo("acme/engine");
        assertThat(item.prUrl()).isEqualTo("https://github.com/acme/engine/pull/142");

        // Lead time = deployed - firstCommit
        long expectedLeadTime = deployed.getEpochSecond() - firstCommit.getEpochSecond();
        assertThat(item.leadTimeSeconds()).isEqualTo(expectedLeadTime);

        // Coding = opened - firstCommit
        assertThat(item.codingTimeSeconds())
            .isEqualTo(opened.getEpochSecond() - firstCommit.getEpochSecond());

        // Review = merged - opened
        assertThat(item.reviewTimeSeconds())
            .isEqualTo(merged.getEpochSecond() - opened.getEpochSecond());

        // Deploy = deployed - merged
        assertThat(item.deployTimeSeconds())
            .isEqualTo(deployed.getEpochSecond() - merged.getEpochSecond());

        assertThat(item.deploymentEnvironment()).isEqualTo("production");
        assertThat(item.deploymentCommitSha()).isEqualTo("abc1234");
    }

    @Test
    void returnsEmptyResponseWhenNoRepositoriesAreAccessible() {
        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());

        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to   = Instant.parse("2026-09-21T00:00:00Z");
        ChangeLeadTimeDetailsResponse response = metricService.getChangeLeadTimeDetails(
            managerPrincipal, null, null, from, to, 0, 20
        );

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.repositoryCount()).isZero();
    }

    @Test
    void handlesNullMergedAtGracefully() {
        // PR opened and first-committed but never merged → reviewTime and deployTime are null
        Instant firstCommit = Instant.parse("2026-09-15T10:00:00Z");
        Instant opened      = Instant.parse("2026-09-15T11:00:00Z");
        Instant deployed    = Instant.parse("2026-09-16T10:00:00Z");

        UUID prId = UUID.randomUUID();
        ChangeLeadTimeRow row = mockRow(
            prId, 99, "WIP feature", "dev",
            repositoryId, "engine", "acme", "acme/engine",
            firstCommit, opened, null, deployed,
            "production", "def5678"
        );

        when(gitRepositoryRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(repository));
        when(pullRequestRepository.findChangeLeadTimeRows(any(), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(row)));

        ChangeLeadTimeDetailsResponse response = metricService.getChangeLeadTimeDetails(
            managerPrincipal, null, null,
            firstCommit.minusSeconds(3600),
            deployed.plusSeconds(3600),
            0, 20
        );

        var item = response.items().get(0);
        assertThat(item.leadTimeSeconds()).isNotNull();
        assertThat(item.codingTimeSeconds()).isNotNull();
        assertThat(item.reviewTimeSeconds()).isNull(); // merged_at was null
        assertThat(item.deployTimeSeconds()).isNull(); // merged_at was null
    }
}
