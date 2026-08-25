package com.adept.api.integration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.common.domain.ExternalProvider;
import com.adept.api.common.domain.GithubAccountType;
import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.RepositorySelection;
import com.adept.api.common.domain.RepositoryVisibility;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.integration.common.IntegrationOauthState;
import com.adept.api.integration.common.IntegrationOauthStateService;
import com.adept.api.integration.github.dto.GithubConnectUrlResponse;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.user.User;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class GithubIntegrationServiceTest {

    @Mock private GithubIntegrationRepository githubIntegrationRepository;
    @Mock private GitRepositoryRepository gitRepositoryRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ProcessingJobRepository processingJobRepository;
    @Mock private IntegrationOauthStateService oauthStateService;
    @Mock private GithubApiClient githubApiClient;
    @Mock private GithubAppTokenService githubAppTokenService;
    @Mock private AuditService auditService;

    private Clock clock;
    private ObjectMapper objectMapper;
    private GithubIntegrationService service;

    private Workspace testWorkspace;
    private Membership managerMembership;
    private Membership leadMembership;
    private User testUser;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneOffset.UTC);
        objectMapper = new JsonMapper();

        AppProperties properties = new AppProperties(
            URI.create("http://localhost:3000"),
            URI.create("http://localhost:8080"),
            "test@adept.local",
            new AppProperties.Jwt("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "adept-api", "adept-frontend", java.time.Duration.ofMinutes(15)),
            new AppProperties.Auth(12, java.time.Duration.ofHours(24), java.time.Duration.ofHours(1), java.time.Duration.ofMinutes(5),
                new AppProperties.RateLimit(30000, java.time.Duration.ofMinutes(15), 10, java.time.Duration.ofMinutes(15), 3, java.time.Duration.ofHours(1), 5, java.time.Duration.ofHours(1), 10, java.time.Duration.ofMinutes(15), 10, java.time.Duration.ofMinutes(15), 100000)),
            new AppProperties.RefreshToken(java.time.Duration.ofDays(7), "adept_refresh", false, "Strict"),
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
            new AppProperties.IntegrationEncryption(1, Map.of(1, "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=")),
            new AppProperties.Github(true, "123", "adept-test", "dGVzdC1vbmx5", "secret"),
            new AppProperties.Jira(true, "client-123", "secret-123", URI.create("http://localhost:8080/callback")),
            new AppProperties.Engine(URI.create("http://localhost:8000"), "internal-token")
        );

        service = new GithubIntegrationService(
            properties,
            githubIntegrationRepository,
            gitRepositoryRepository,
            workspaceRepository,
            processingJobRepository,
            oauthStateService,
            githubApiClient,
            githubAppTokenService,
            auditService,
            clock,
            objectMapper
        );

        testWorkspace = new Workspace();
        testWorkspace.setId(UUID.randomUUID());
        testWorkspace.setName("Test Workspace");

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("manager@example.com");

        managerMembership = new Membership();
        managerMembership.setId(UUID.randomUUID());
        managerMembership.setWorkspace(testWorkspace);
        managerMembership.setUser(testUser);
        managerMembership.setRole(MembershipRole.MANAGER);

        leadMembership = new Membership();
        leadMembership.setId(UUID.randomUUID());
        leadMembership.setWorkspace(testWorkspace);
        leadMembership.setUser(testUser);
        leadMembership.setRole(MembershipRole.LEAD);
    }

    @Test
    @DisplayName("createConnectUrl requires MANAGER role")
    void createConnectUrlRequiresManager() {
        assertThatThrownBy(() -> service.createConnectUrl(testWorkspace.getId(), leadMembership))
            .isInstanceOf(ApiException.class)
            .matches(e -> ((ApiException) e).code() == ProblemCode.MANAGER_REQUIRED);
    }

    @Test
    @DisplayName("createConnectUrl issues state and returns GitHub app installation link")
    void createConnectUrlSuccess() {
        when(workspaceRepository.findById(testWorkspace.getId())).thenReturn(Optional.of(testWorkspace));
        IntegrationOauthState stateEntity = new IntegrationOauthState();
        when(oauthStateService.issueState(eq(ExternalProvider.GITHUB), eq(testWorkspace), eq(managerMembership), eq(null), any()))
            .thenReturn(new IntegrationOauthStateService.IssuedOauthState("raw-random-state", stateEntity));

        GithubConnectUrlResponse response = service.createConnectUrl(testWorkspace.getId(), managerMembership);

        assertThat(response.state()).isEqualTo("raw-random-state");
        assertThat(response.url()).isEqualTo("https://github.com/apps/adept-test/installations/new?state=raw-random-state");
    }

    @Test
    @DisplayName("handleCallback connects installation, syncs repos, and records audit")
    void handleCallbackSuccess() {
        long installationId = 777L;
        String rawState = "valid-state";

        when(oauthStateService.consumeState(ExternalProvider.GITHUB, rawState))
            .thenReturn(new IntegrationOauthStateService.ConsumedOauthState(
                testWorkspace,
                managerMembership,
                null,
                "/dashboard/integrations"
            ));

        when(githubIntegrationRepository.findByInstallationId(installationId)).thenReturn(Optional.empty());
        when(githubApiClient.getInstallation(installationId)).thenReturn(
            new GithubApiClient.GithubInstallationDetails(
                installationId,
                888L,
                "acme-org",
                GithubAccountType.ORGANIZATION,
                RepositorySelection.ALL,
                Map.of("issues", "read")
            )
        );

        when(githubApiClient.listInstallationRepositories(installationId)).thenReturn(
            List.of(
                new GithubApiClient.GithubRepoDetails(
                    999L,
                    "node-999",
                    "acme-org",
                    "my-repo",
                    "acme-org/my-repo",
                    "main",
                    RepositoryVisibility.PRIVATE,
                    false
                )
            )
        );

        String redirectUrl = service.handleCallback(installationId, rawState);

        assertThat(redirectUrl).contains("github=connected");
        verify(githubIntegrationRepository, org.mockito.Mockito.atLeastOnce()).save(any(GithubIntegration.class));
        verify(gitRepositoryRepository).save(any(GitRepository.class));
        verify(auditService).record(
            eq(AuditAction.GITHUB_INTEGRATION_CONNECTED),
            eq(testUser),
            eq(managerMembership),
            eq(testWorkspace),
            eq("GITHUB_INTEGRATION"),
            any(),
            any()
        );
    }

    @Test
    @DisplayName("sync disables repositories removed from the installation without marking them archived")
    void syncRemovedRepositoryDisablesTrackingButPreservesArchiveState() {
        GithubIntegration integration = new GithubIntegration();
        integration.setId(UUID.randomUUID());
        integration.setWorkspace(testWorkspace);
        integration.setInstallationId(777L);
        integration.setStatus(IntegrationStatus.ACTIVE);

        GitRepository repository = new GitRepository();
        repository.setId(UUID.randomUUID());
        repository.setWorkspace(testWorkspace);
        repository.setGithubIntegration(integration);
        repository.setGithubRepoId(999L);
        repository.setTrackingEnabled(true);
        repository.setArchived(false);

        when(githubIntegrationRepository.findByIdAndWorkspaceId(
                integration.getId(), testWorkspace.getId()))
            .thenReturn(Optional.of(integration));
        when(githubApiClient.listInstallationRepositories(777L)).thenReturn(List.of());
        when(gitRepositoryRepository.findAllByGithubIntegrationId(integration.getId()))
            .thenReturn(List.of(repository));

        service.syncRepositories(testWorkspace.getId(), integration.getId(), managerMembership);

        assertThat(repository.isTrackingEnabled()).isFalse();
        assertThat(repository.isArchived()).isFalse();
        verify(gitRepositoryRepository).save(repository);
    }

    @Test
    @DisplayName("sync disables tracking when GitHub archives a repository")
    void syncArchivedRepositoryDisablesTracking() {
        GithubIntegration integration = new GithubIntegration();
        integration.setId(UUID.randomUUID());
        integration.setWorkspace(testWorkspace);
        integration.setInstallationId(777L);
        integration.setStatus(IntegrationStatus.ACTIVE);

        GitRepository repository = new GitRepository();
        repository.setId(UUID.randomUUID());
        repository.setWorkspace(testWorkspace);
        repository.setGithubIntegration(integration);
        repository.setGithubRepoId(999L);
        repository.setTrackingEnabled(true);
        repository.setArchived(false);

        when(githubIntegrationRepository.findByIdAndWorkspaceId(
                integration.getId(), testWorkspace.getId()))
            .thenReturn(Optional.of(integration));
        when(githubApiClient.listInstallationRepositories(777L)).thenReturn(List.of(
            new GithubApiClient.GithubRepoDetails(
                999L,
                "node-999",
                "acme-org",
                "my-repo",
                "acme-org/my-repo",
                "main",
                RepositoryVisibility.PRIVATE,
                true
            )
        ));
        when(gitRepositoryRepository.findByWorkspaceIdAndGithubRepoId(testWorkspace.getId(), 999L))
            .thenReturn(Optional.of(repository));
        when(gitRepositoryRepository.findAllByGithubIntegrationId(integration.getId()))
            .thenReturn(List.of(repository));

        service.syncRepositories(testWorkspace.getId(), integration.getId(), managerMembership);

        assertThat(repository.isArchived()).isTrue();
        assertThat(repository.isTrackingEnabled()).isFalse();
        verify(gitRepositoryRepository).save(repository);
    }
}
