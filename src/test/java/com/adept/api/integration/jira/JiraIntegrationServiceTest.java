package com.adept.api.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.common.domain.ExternalProvider;
import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.IntegrationEncryptionService;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.integration.common.IntegrationOauthState;
import com.adept.api.integration.common.IntegrationOauthStateService;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.jira.dto.JiraConnectUrlResponse;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.user.User;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

@ExtendWith(MockitoExtension.class)
class JiraIntegrationServiceTest {

    @Mock private JiraIntegrationRepository jiraIntegrationRepository;
    @Mock private JiraProjectRepository jiraProjectRepository;
    @Mock private RepositoryJiraProjectRepository repositoryJiraProjectRepository;
    @Mock private GitRepositoryRepository gitRepositoryRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ProcessingJobRepository processingJobRepository;
    @Mock private IntegrationOauthStateService oauthStateService;
    @Mock private JiraOAuthClient jiraOAuthClient;
    @Mock private JiraApiClient jiraApiClient;
    @Mock private SecureTokenGenerator tokenGenerator;
    @Mock private TokenHasher tokenHasher;
    @Mock private AuditService auditService;

    private Clock clock;
    private IntegrationEncryptionService encryptionService;
    private JiraIntegrationService service;

    private Workspace testWorkspace;
    private Membership managerMembership;
    private Membership leadMembership;
    private User testUser;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-15T10:00:00Z"), ZoneOffset.UTC);
        SecureRandom random = new SecureRandom();
        byte[] keyBytes = new byte[32];
        random.nextBytes(keyBytes);
        encryptionService = new IntegrationEncryptionService(
            1,
            Map.of(1, new SecretKeySpec(keyBytes, "AES")),
            random
        );

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

        service = new JiraIntegrationService(
            properties,
            jiraIntegrationRepository,
            jiraProjectRepository,
            repositoryJiraProjectRepository,
            gitRepositoryRepository,
            workspaceRepository,
            processingJobRepository,
            oauthStateService,
            jiraOAuthClient,
            jiraApiClient,
            encryptionService,
            tokenGenerator,
            tokenHasher,
            auditService,
            clock
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
    @DisplayName("createConnectUrl builds Jira authorization URL with PKCE")
    void createConnectUrlSuccess() {
        when(workspaceRepository.findById(testWorkspace.getId())).thenReturn(Optional.of(testWorkspace));
        when(tokenGenerator.generate()).thenReturn("abcdef1234567890abcdef1234567890");
        when(oauthStateService.issueState(eq(ExternalProvider.JIRA), eq(testWorkspace), eq(managerMembership), any(), any()))
            .thenReturn(new IntegrationOauthStateService.IssuedOauthState("raw-jira-state", new IntegrationOauthState()));
        when(jiraOAuthClient.buildAuthorizationUrl(eq("raw-jira-state"), any()))
            .thenReturn("https://auth.atlassian.com/authorize?state=raw-jira-state");

        JiraConnectUrlResponse response = service.createConnectUrl(testWorkspace.getId(), managerMembership);

        assertThat(response.state()).isEqualTo("raw-jira-state");
        assertThat(response.url()).contains("https://auth.atlassian.com/authorize");
    }

    @Test
    @DisplayName("mapProjectsToRepository rejects projects from a different workspace")
    void mapProjectsRejectsCrossWorkspace() {
        GitRepository repository = new GitRepository();
        repository.setId(UUID.randomUUID());
        repository.setWorkspace(testWorkspace);

        when(gitRepositoryRepository.findByIdAndWorkspaceId(repository.getId(), testWorkspace.getId()))
            .thenReturn(Optional.of(repository));

        UUID otherWorkspaceProjectId = UUID.randomUUID();
        when(jiraProjectRepository.findAllByIdInAndWorkspaceId(List.of(otherWorkspaceProjectId), testWorkspace.getId()))
            .thenReturn(List.of()); // None found in this workspace

        assertThatThrownBy(() -> service.mapProjectsToRepository(
            testWorkspace.getId(),
            repository.getId(),
            List.of(otherWorkspaceProjectId),
            managerMembership
        ))
            .isInstanceOf(ApiException.class)
            .matches(e -> ((ApiException) e).code() == ProblemCode.JIRA_PROJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("disconnect revokes locally even when stored provider credentials are unreadable")
    void disconnectRevokesLocallyWhenCredentialCleanupFails() {
        JiraIntegration integration = new JiraIntegration();
        integration.setId(UUID.randomUUID());
        integration.setWorkspace(testWorkspace);
        integration.setCloudId("cloud-123");
        integration.setSiteUrl("https://adept-test.atlassian.net");
        integration.setWebhookId(1000L);
        integration.setWebhookTokenHash("a".repeat(64));
        integration.setWebhookExpiresAt(clock.instant().plus(java.time.Duration.ofDays(20)));
        integration.setAccessTokenEnc("not-an-encrypted-token");
        integration.setRefreshTokenEnc("not-an-encrypted-token");
        integration.setEncryptionKeyVersion(1);
        integration.setAccessTokenExpiresAt(clock.instant().plus(java.time.Duration.ofHours(1)));

        when(jiraIntegrationRepository.findById(integration.getId()))
            .thenReturn(Optional.of(integration));
        when(jiraProjectRepository.findAllByJiraIntegrationId(integration.getId()))
            .thenReturn(List.of());

        service.disconnect(testWorkspace.getId(), integration.getId(), managerMembership);

        assertThat(integration.getStatus())
            .isEqualTo(com.adept.api.common.domain.IntegrationStatus.REVOKED);
        assertThat(integration.getWebhookId()).isNull();
        assertThat(integration.getWebhookTokenHash()).isNull();
        assertThat(integration.getWebhookExpiresAt()).isNull();
        verify(jiraIntegrationRepository).save(integration);
        verify(jiraApiClient, never()).deleteWebhook(any(), any(), anyLong());
    }

    @Test
    @DisplayName("manager can request one durable Jira project catalog sync")
    void requestProjectSyncQueuesOneDurableJob() {
        JiraIntegration integration = connectedIntegration();
        integration.setStatus(IntegrationStatus.ACTIVE);
        when(jiraIntegrationRepository.findAllByWorkspaceIdForUpdate(testWorkspace.getId()))
            .thenReturn(List.of(integration));
        when(processingJobRepository.findActiveJiraProjectSyncForUpdate(
                integration.getId().toString()))
            .thenReturn(Optional.empty());

        service.requestProjectSync(
            testWorkspace.getId(),
            integration.getId(),
            managerMembership
        );

        ArgumentCaptor<ProcessingJob> captor = ArgumentCaptor.forClass(ProcessingJob.class);
        verify(processingJobRepository).save(captor.capture());
        assertThat(captor.getValue().getJobType())
            .isEqualTo(ProcessingJobType.SYNC_JIRA_PROJECTS);
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessingJobStatus.PENDING);
        assertThat(captor.getValue().getPayload())
            .containsEntry("workspaceId", testWorkspace.getId().toString())
            .containsEntry("jiraIntegrationId", integration.getId().toString());
    }

    @Test
    @DisplayName("callback replaces a stale dynamic webhook with a new token")
    void callbackReplacesStaleWebhook() {
        JiraIntegration integration = connectedIntegration();
        integration.setWebhookId(1000L);
        integration.setWebhookTokenHash("a".repeat(64));
        stubCallback(integration);
        when(jiraApiClient.webhookExists("cloud-123", "access-token", 1000L))
            .thenReturn(false);
        when(tokenGenerator.generate()).thenReturn("A".repeat(43));
        when(tokenHasher.hashJiraWebhookToken("A".repeat(43)))
            .thenReturn("b".repeat(64));
        when(jiraApiClient.registerWebhook(eq("cloud-123"), eq("access-token"), any()))
            .thenReturn(2000L);

        String redirect = service.handleCallback("code", "state");

        assertThat(redirect).contains("jira=connected");
        assertThat(integration.getWebhookId()).isEqualTo(2000L);
        assertThat(integration.getWebhookTokenHash()).isEqualTo("b".repeat(64));
        verify(jiraApiClient, never()).refreshWebhook(any(), any(), anyLong());
        verify(jiraApiClient).registerWebhook(eq("cloud-123"), eq("access-token"), any());
    }

    @Test
    @DisplayName("callback deletes a newly registered webhook when later project sync fails")
    void callbackCompensatesRemoteWebhookWhenTransactionWorkFails() {
        stubCallback(null);
        when(tokenGenerator.generate()).thenReturn("A".repeat(43));
        when(tokenHasher.hashJiraWebhookToken("A".repeat(43)))
            .thenReturn("b".repeat(64));
        when(jiraApiClient.registerWebhook(eq("cloud-123"), eq("access-token"), any()))
            .thenReturn(2000L);
        when(jiraApiClient.listProjects("cloud-123", "access-token"))
            .thenThrow(new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "Project sync failed"
            ));

        assertThatThrownBy(() -> service.handleCallback("code", "state"))
            .isInstanceOf(ApiException.class)
            .matches(exception -> ((ApiException) exception).code()
                == ProblemCode.INTEGRATION_PROVIDER_ERROR);

        verify(jiraApiClient).deleteWebhook("cloud-123", "access-token", 2000L);
    }

    @Test
    @DisplayName("callback compensates when the renewal job cannot be flushed")
    void callbackCompensatesRemoteWebhookWhenRenewalFlushFails() {
        stubCallback(null);
        when(tokenGenerator.generate()).thenReturn("A".repeat(43));
        when(tokenHasher.hashJiraWebhookToken("A".repeat(43)))
            .thenReturn("b".repeat(64));
        when(jiraApiClient.registerWebhook(eq("cloud-123"), eq("access-token"), any()))
            .thenReturn(2000L);
        when(processingJobRepository.saveAndFlush(any(ProcessingJob.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate renewal"));

        assertThatThrownBy(() -> service.handleCallback("code", "state"))
            .isInstanceOf(DataIntegrityViolationException.class);

        verify(jiraApiClient).deleteWebhook("cloud-123", "access-token", 2000L);
    }

    @Test
    @DisplayName("callback compensates a newly registered webhook after transaction rollback")
    void callbackCompensatesRemoteWebhookAfterCommitTimeRollback() {
        stubCallback(null);
        when(tokenGenerator.generate()).thenReturn("A".repeat(43));
        when(tokenHasher.hashJiraWebhookToken("A".repeat(43)))
            .thenReturn("b".repeat(64));
        when(jiraApiClient.registerWebhook(eq("cloud-123"), eq("access-token"), any()))
            .thenReturn(2000L);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.handleCallback("code", "state");
            TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK
                )
            );
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(jiraApiClient).deleteWebhook("cloud-123", "access-token", 2000L);
    }

    @Test
    @DisplayName("callback leaves a running webhook renewal job under worker ownership")
    void callbackDoesNotRaceRunningWebhookRenewal() {
        JiraIntegration integration = connectedIntegration();
        integration.setWebhookId(1000L);
        integration.setWebhookTokenHash("a".repeat(64));
        stubCallback(integration);
        Instant refreshedExpiry = clock.instant().plus(java.time.Duration.ofDays(30));
        when(jiraApiClient.refreshWebhook("cloud-123", "access-token", 1000L))
            .thenReturn(refreshedExpiry);
        ProcessingJob runningRenewal = new ProcessingJob();
        runningRenewal.setStatus(ProcessingJobStatus.RUNNING);
        when(processingJobRepository.findScheduledJiraWebhookRenewalForUpdate(
                integration.getId().toString()))
            .thenReturn(Optional.of(runningRenewal));

        service.handleCallback("code", "state");

        assertThat(runningRenewal.getStatus()).isEqualTo(ProcessingJobStatus.RUNNING);
        verify(processingJobRepository, never()).saveAndFlush(any(ProcessingJob.class));
    }

    private JiraIntegration connectedIntegration() {
        JiraIntegration integration = new JiraIntegration();
        integration.setId(UUID.randomUUID());
        integration.setWorkspace(testWorkspace);
        integration.setCloudId("cloud-123");
        integration.setSiteUrl("https://adept-test.atlassian.net");
        integration.setDisplayName("Adept Jira");
        return integration;
    }

    private void stubCallback(JiraIntegration existingIntegration) {
        when(oauthStateService.consumeState(ExternalProvider.JIRA, "state"))
            .thenReturn(new IntegrationOauthStateService.ConsumedOauthState(
                testWorkspace,
                managerMembership,
                "code-verifier",
                "/dashboard/integrations"
            ));
        when(jiraOAuthClient.exchangeCode("code", "code-verifier"))
            .thenReturn(new JiraOAuthClient.JiraTokenResponse(
                "access-token",
                "refresh-token",
                3600,
                new String[]{"read:jira-work", "manage:jira-webhook", "offline_access"}
            ));
        when(jiraOAuthClient.getAccessibleResources("access-token"))
            .thenReturn(List.of(new JiraOAuthClient.JiraAccessibleResource(
                "cloud-123",
                "https://adept-test.atlassian.net",
                "Adept Jira",
                null
            )));
        when(jiraIntegrationRepository.findAllByWorkspaceIdForUpdate(testWorkspace.getId()))
            .thenReturn(existingIntegration == null ? List.of() : List.of(existingIntegration));
        when(jiraIntegrationRepository.saveAndFlush(any(JiraIntegration.class)))
            .thenAnswer(invocation -> {
                JiraIntegration integration = invocation.getArgument(0);
                if (integration.getId() == null) {
                    integration.setId(UUID.randomUUID());
                }
                return integration;
            });
        when(processingJobRepository.findScheduledJiraWebhookRenewalForUpdate(any()))
            .thenReturn(Optional.empty());
        if (existingIntegration != null) {
            when(jiraApiClient.webhookExists(
                    "cloud-123", "access-token", existingIntegration.getWebhookId()))
                .thenReturn(true);
            when(jiraApiClient.listProjects("cloud-123", "access-token"))
                .thenReturn(List.of());
        }
    }
}
