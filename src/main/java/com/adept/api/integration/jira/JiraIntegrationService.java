package com.adept.api.integration.jira;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.UriComponentsBuilder;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.common.domain.ExternalProvider;
import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.IntegrationEncryptionService;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.integration.common.IntegrationOauthStateService;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.jira.dto.JiraConnectUrlResponse;
import com.adept.api.integration.jira.dto.JiraIntegrationResponse;
import com.adept.api.integration.jira.dto.JiraProjectResponse;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

@ConditionalOnProperty(name = "app.jira.enabled", havingValue = "true")
@Service
public class JiraIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(JiraIntegrationService.class);
    private static final Duration JIRA_WEBHOOK_TTL = Duration.ofDays(30);
    private static final Duration JIRA_WEBHOOK_RENEWAL_LEAD = Duration.ofDays(5);

    private final AppProperties properties;
    private final JiraIntegrationRepository jiraIntegrationRepository;
    private final JiraProjectRepository jiraProjectRepository;
    private final RepositoryJiraProjectRepository repositoryJiraProjectRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final IntegrationOauthStateService oauthStateService;
    private final JiraOAuthClient jiraOAuthClient;
    private final JiraApiClient jiraApiClient;
    private final IntegrationEncryptionService encryptionService;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final AuditService auditService;
    private final Clock clock;

    public JiraIntegrationService(
            AppProperties properties,
            JiraIntegrationRepository jiraIntegrationRepository,
            JiraProjectRepository jiraProjectRepository,
            RepositoryJiraProjectRepository repositoryJiraProjectRepository,
            GitRepositoryRepository gitRepositoryRepository,
            WorkspaceRepository workspaceRepository,
            ProcessingJobRepository processingJobRepository,
            IntegrationOauthStateService oauthStateService,
            JiraOAuthClient jiraOAuthClient,
            JiraApiClient jiraApiClient,
            IntegrationEncryptionService encryptionService,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            AuditService auditService,
            Clock clock) {
        this.properties = properties;
        this.jiraIntegrationRepository = jiraIntegrationRepository;
        this.jiraProjectRepository = jiraProjectRepository;
        this.repositoryJiraProjectRepository = repositoryJiraProjectRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.processingJobRepository = processingJobRepository;
        this.oauthStateService = oauthStateService;
        this.jiraOAuthClient = jiraOAuthClient;
        this.jiraApiClient = jiraApiClient;
        this.encryptionService = encryptionService;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public JiraConnectUrlResponse createConnectUrl(UUID workspaceId, Membership membership) {
        verifyManagerRole(membership);

        if (!properties.jira().enabled()) {
            throw new ApiException(ProblemCode.INTEGRATION_DISABLED, "Jira integration is not enabled");
        }

        Workspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.WORKSPACE_NOT_FOUND));

        String codeVerifier = tokenGenerator.generate() + tokenGenerator.generate();
        String codeChallenge = JiraOAuthClient.generateCodeChallenge(codeVerifier);

        IntegrationOauthStateService.IssuedOauthState issued = oauthStateService.issueState(
            ExternalProvider.JIRA,
            workspace,
            membership,
            codeVerifier,
            "/dashboard/integrations"
        );

        String authUrl = jiraOAuthClient.buildAuthorizationUrl(issued.rawState(), codeChallenge);
        return new JiraConnectUrlResponse(authUrl, issued.rawState());
    }

    @Transactional
    public String handleCallback(String code, String rawState) {
        IntegrationOauthStateService.ConsumedOauthState consumedState =
            oauthStateService.consumeState(ExternalProvider.JIRA, rawState);

        Workspace workspace = consumedState.workspace();
        Membership initiatedBy = consumedState.initiatedBy();
        String codeVerifier = consumedState.codeVerifier();

        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new ApiException(ProblemCode.INTEGRATION_STATE_INVALID, "Missing PKCE code verifier");
        }

        JiraOAuthClient.JiraTokenResponse tokenResponse =
            jiraOAuthClient.exchangeCode(code, codeVerifier);

        List<JiraOAuthClient.JiraAccessibleResource> resources =
            jiraOAuthClient.getAccessibleResources(tokenResponse.accessToken());

        JiraOAuthClient.JiraAccessibleResource primaryResource = resources.get(0);
        String cloudId = primaryResource.id();

        IntegrationEncryptionService.EncryptedPayload encryptedAccessToken =
            encryptionService.encrypt(tokenResponse.accessToken());
        IntegrationEncryptionService.EncryptedPayload encryptedRefreshToken =
            encryptionService.encrypt(tokenResponse.refreshToken());

        Instant expiresAt = clock.instant().plus(Duration.ofSeconds(tokenResponse.expiresInSeconds()));

        List<JiraIntegration> existingList =
            jiraIntegrationRepository.findAllByWorkspaceIdForUpdate(workspace.getId());
        Optional<JiraIntegration> existingOpt = existingList.stream()
            .filter(i -> cloudId.equals(i.getCloudId()))
            .findFirst();

        JiraIntegration integration = existingOpt.orElseGet(JiraIntegration::new);
        integration.setWorkspace(workspace);
        integration.setCloudId(cloudId);
        integration.setSiteUrl(primaryResource.url());
        integration.setDisplayName(primaryResource.name());
        integration.setAccessTokenEnc(encryptedAccessToken.ciphertext());
        integration.setRefreshTokenEnc(encryptedRefreshToken.ciphertext());
        integration.setEncryptionKeyVersion(encryptedAccessToken.keyVersion());
        integration.setAccessTokenExpiresAt(expiresAt);
        integration.setScopes(tokenResponse.scopes());
        integration.setStatus(IntegrationStatus.ACTIVE);
        integration.setConnectedBy(initiatedBy);
        integration.setLastSyncedAt(clock.instant());

        jiraIntegrationRepository.saveAndFlush(integration);

        WebhookProvisioning webhookProvisioning = null;
        try {
            webhookProvisioning = provisionDynamicWebhook(
                integration,
                tokenResponse.accessToken()
            );
            registerRollbackCompensation(
                webhookProvisioning,
                integration,
                tokenResponse.accessToken()
            );

            // Sync initial projects
            syncProjectsInternal(workspace, integration, tokenResponse.accessToken());

            // Audit log
            auditService.record(
                AuditAction.JIRA_INTEGRATION_CONNECTED,
                initiatedBy.getUser(),
                initiatedBy,
                workspace,
                "JIRA_INTEGRATION",
                integration.getId(),
                Map.of(
                    "cloudId", cloudId,
                    "siteUrl", primaryResource.url(),
                    "displayName", primaryResource.name()
                )
            );
        } catch (RuntimeException exception) {
            // A new remote webhook contains a one-time callback token. If this
            // transaction cannot commit the matching hash, compensate so Jira is
            // not left calling an endpoint with an unrecoverable credential.
            if (webhookProvisioning != null && webhookProvisioning.created()) {
                compensateProvisionedWebhook(
                    webhookProvisioning,
                    integration,
                    tokenResponse.accessToken()
                );
            }
            throw exception;
        }

        return properties.frontendBaseUrl().resolve("/dashboard/integrations?jira=connected").toString();
    }

    @Transactional(readOnly = true)
    public Optional<JiraIntegrationResponse> getIntegration(UUID workspaceId) {
        List<JiraIntegration> integrations = jiraIntegrationRepository.findAllByWorkspaceId(workspaceId);
        if (integrations.isEmpty()) {
            return Optional.empty();
        }

        JiraIntegration active = integrations.stream()
            .filter(i -> i.getStatus() == IntegrationStatus.ACTIVE)
            .findFirst()
            .orElse(integrations.get(0));

        int projectCount = jiraProjectRepository.countByJiraIntegrationId(active.getId());
        return Optional.of(toResponse(active, projectCount));
    }

    @Transactional(readOnly = true)
    public List<JiraProjectResponse> listProjects(UUID workspaceId, Membership membership) {
        List<JiraProject> projects = jiraProjectRepository.findAllByWorkspaceId(workspaceId);
        return projects.stream().map(this::toProjectResponse).toList();
    }

    @Transactional
    public JiraProjectResponse updateProjectTracking(
            UUID workspaceId,
            UUID projectId,
            boolean trackingEnabled,
            Membership membership) {
        verifyManagerRole(membership);

        JiraProject project = jiraProjectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.JIRA_PROJECT_NOT_FOUND));

        project.setTrackingEnabled(trackingEnabled);
        jiraProjectRepository.save(project);

        auditService.record(
            AuditAction.JIRA_PROJECT_TRACKING_UPDATED,
            membership.getUser(),
            membership,
            project.getWorkspace(),
            "JIRA_PROJECT",
            project.getId(),
            Map.of(
                "projectId", project.getId().toString(),
                "projectKey", project.getProjectKey(),
                "trackingEnabled", trackingEnabled
            )
        );

        return toProjectResponse(project);
    }

    @Transactional
    public void mapProjectsToRepository(
            UUID workspaceId,
            UUID repositoryId,
            List<UUID> jiraProjectIds,
            Membership membership) {
        verifyManagerRole(membership);

        GitRepository repository = gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.REPOSITORY_NOT_FOUND));

        List<JiraProject> targetProjects = List.of();
        if (jiraProjectIds != null && !jiraProjectIds.isEmpty()) {
            targetProjects = jiraProjectRepository.findAllByIdInAndWorkspaceId(jiraProjectIds, workspaceId);
            if (targetProjects.size() != jiraProjectIds.size()) {
                throw new ApiException(
                    ProblemCode.JIRA_PROJECT_NOT_FOUND,
                    "One or more Jira projects do not exist or belong to another workspace"
                );
            }
        }

        // Replace mappings atomically
        repositoryJiraProjectRepository.deleteAllByRepositoryId(repository.getId());

        List<RepositoryJiraProject> mappings = new ArrayList<>();
        for (JiraProject jp : targetProjects) {
            mappings.add(RepositoryJiraProject.create(repository, jp, clock.instant()));
        }
        repositoryJiraProjectRepository.saveAll(mappings);

        auditService.record(
            AuditAction.REPOSITORY_JIRA_PROJECTS_UPDATED,
            membership.getUser(),
            membership,
            repository.getWorkspace(),
            "REPOSITORY",
            repository.getId(),
            Map.of(
                "repositoryId", repository.getId().toString(),
                "mappedProjectCount", targetProjects.size()
            )
        );
    }

    @Transactional(readOnly = true)
    public List<JiraProjectResponse> getMappedProjectsForRepository(UUID workspaceId, UUID repositoryId) {
        GitRepository repository = gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.REPOSITORY_NOT_FOUND));

        List<RepositoryJiraProject> mappings =
            repositoryJiraProjectRepository.findAllByRepositoryIdWithProject(repository.getId());

        return mappings.stream()
            .map(m -> toProjectResponse(m.getJiraProject()))
            .toList();
    }

    @Transactional
    public void disconnect(UUID workspaceId, UUID integrationId, Membership membership) {
        verifyManagerRole(membership);

        JiraIntegration integration = jiraIntegrationRepository.findById(integrationId)
            .filter(i -> i.getWorkspace().getId().equals(workspaceId))
            .orElseThrow(() -> new ApiException(ProblemCode.INTEGRATION_NOT_FOUND));

        deleteRemoteWebhookBestEffort(integration);
        integration.setStatus(IntegrationStatus.REVOKED);
        integration.setWebhookTokenHash(null);
        integration.setWebhookId(null);
        integration.setWebhookExpiresAt(null);
        jiraIntegrationRepository.save(integration);

        // Disable tracking on projects
        List<JiraProject> projects = jiraProjectRepository.findAllByJiraIntegrationId(integration.getId());
        for (JiraProject p : projects) {
            p.setTrackingEnabled(false);
            jiraProjectRepository.save(p);
        }

        auditService.record(
            AuditAction.JIRA_INTEGRATION_DISCONNECTED,
            membership.getUser(),
            membership,
            integration.getWorkspace(),
            "JIRA_INTEGRATION",
            integration.getId(),
            Map.of(
                "cloudId", integration.getCloudId(),
                "siteUrl", integration.getSiteUrl()
            )
        );
    }

    @Transactional
    public void requestProjectSync(
            UUID workspaceId,
            UUID integrationId,
            Membership membership) {
        verifyManagerRole(membership);

        JiraIntegration integration = jiraIntegrationRepository
            .findAllByWorkspaceIdForUpdate(workspaceId)
            .stream()
            .filter(candidate -> candidate.getId().equals(integrationId))
            .filter(candidate -> candidate.getStatus() == IntegrationStatus.ACTIVE)
            .findFirst()
            .orElseThrow(() -> new ApiException(ProblemCode.INTEGRATION_NOT_FOUND));

        if (processingJobRepository
                .findActiveJiraProjectSyncForUpdate(integrationId.toString())
                .isPresent()) {
            return;
        }

        ProcessingJob job = new ProcessingJob();
        job.setWorkspace(integration.getWorkspace());
        job.setJobType(ProcessingJobType.SYNC_JIRA_PROJECTS);
        job.setStatus(ProcessingJobStatus.PENDING);
        job.setPriority(50);
        job.setPayload(Map.of(
            "workspaceId", workspaceId.toString(),
            "jiraIntegrationId", integrationId.toString()
        ));
        job.setAvailableAt(clock.instant());
        processingJobRepository.save(job);
    }

    @Transactional
    public String getValidAccessToken(JiraIntegration integration) {
        Instant now = clock.instant();
        // Refresh token if within 5 minutes of expiration
        if (integration.getAccessTokenExpiresAt().isBefore(now.plus(Duration.ofMinutes(5)))) {
            String currentRefreshToken = encryptionService.decrypt(
                integration.getRefreshTokenEnc(),
                integration.getEncryptionKeyVersion()
            );

            JiraOAuthClient.JiraTokenResponse newTokens =
                jiraOAuthClient.refreshToken(currentRefreshToken);

            IntegrationEncryptionService.EncryptedPayload encAccess =
                encryptionService.encrypt(newTokens.accessToken());
            IntegrationEncryptionService.EncryptedPayload encRefresh =
                encryptionService.encrypt(newTokens.refreshToken());

            integration.setAccessTokenEnc(encAccess.ciphertext());
            integration.setRefreshTokenEnc(encRefresh.ciphertext());
            integration.setEncryptionKeyVersion(encAccess.keyVersion());
            integration.setAccessTokenExpiresAt(now.plus(Duration.ofSeconds(newTokens.expiresInSeconds())));
            jiraIntegrationRepository.save(integration);

            return newTokens.accessToken();
        }

        return encryptionService.decrypt(
            integration.getAccessTokenEnc(),
            integration.getEncryptionKeyVersion()
        );
    }

    private void syncProjectsInternal(Workspace workspace, JiraIntegration integration, String accessToken) {
        List<JiraApiClient.JiraProjectDetails> remoteProjects =
            jiraApiClient.listProjects(integration.getCloudId(), accessToken);

        Instant now = clock.instant();
        Set<String> remoteKeys = new HashSet<>();

        for (JiraApiClient.JiraProjectDetails remote : remoteProjects) {
            remoteKeys.add(remote.key());

            Optional<JiraProject> existing =
                jiraProjectRepository.findByWorkspaceIdAndProjectKey(workspace.getId(), remote.key());

            JiraProject project = existing.orElseGet(JiraProject::new);
            project.setWorkspace(workspace);
            project.setJiraIntegration(integration);
            project.setJiraProjectId(remote.id());
            project.setProjectKey(remote.key());
            project.setProjectName(remote.name());
            project.setProjectType(remote.projectType());
            project.setLastSyncedAt(now);

            if (existing.isEmpty()) {
                project.setTrackingEnabled(false);
            }

            jiraProjectRepository.save(project);
        }

        integration.setLastSyncedAt(now);
        jiraIntegrationRepository.save(integration);
    }

    private WebhookProvisioning provisionDynamicWebhook(
            JiraIntegration integration,
            String accessToken) {
        Instant now = clock.instant();

        if (integration.getWebhookId() != null && integration.getWebhookTokenHash() != null) {
            try {
                if (!jiraApiClient.webhookExists(
                        integration.getCloudId(),
                        accessToken,
                        integration.getWebhookId())) {
                    throw new JiraApiClient.JiraWebhookNotFoundException();
                }
                Instant refreshedExpiry = jiraApiClient.refreshWebhook(
                    integration.getCloudId(),
                    accessToken,
                    integration.getWebhookId()
                );
                integration.setWebhookExpiresAt(refreshedExpiry);
                jiraIntegrationRepository.save(integration);
                scheduleWebhookRenewal(integration, refreshedExpiry);
                return new WebhookProvisioning(integration.getWebhookId(), false);
            } catch (JiraApiClient.JiraWebhookNotFoundException exception) {
                log.info(
                    "Replacing missing Jira webhook integrationId={}",
                    integration.getId()
                );
            }
        }

        jiraApiClient.deleteAllWebhooks(integration.getCloudId(), accessToken);

        String rawWebhookToken = tokenGenerator.generate();
        String callbackUrl = UriComponentsBuilder
            .fromUri(properties.publicApiBaseUrl())
            .path("/api/v1/webhooks/jira/{integrationId}")
            .queryParam("token", rawWebhookToken)
            .buildAndExpand(integration.getId())
            .toUriString();
        long webhookId = jiraApiClient.registerWebhook(
            integration.getCloudId(),
            accessToken,
            callbackUrl
        );
        WebhookProvisioning provisioning = new WebhookProvisioning(webhookId, true);
        try {
            Instant expiresAt = now.plus(JIRA_WEBHOOK_TTL);

            integration.setWebhookId(webhookId);
            integration.setWebhookExpiresAt(expiresAt);
            integration.setWebhookTokenHash(tokenHasher.hashJiraWebhookToken(rawWebhookToken));
            jiraIntegrationRepository.saveAndFlush(integration);
            scheduleWebhookRenewal(integration, expiresAt);
            return provisioning;
        } catch (RuntimeException exception) {
            compensateProvisionedWebhook(provisioning, integration, accessToken);
            throw exception;
        }
    }

    private void scheduleWebhookRenewal(JiraIntegration integration, Instant expiresAt) {
        Optional<ProcessingJob> existingJob = processingJobRepository
            .findScheduledJiraWebhookRenewalForUpdate(integration.getId().toString());
        if (existingJob
                .map(ProcessingJob::getStatus)
                .filter(status -> status == ProcessingJobStatus.RUNNING)
                .isPresent()) {
            // The worker owns this row and will either requeue it after failure or
            // schedule its successor after success. Creating a second PENDING row
            // here would race both transitions against the V12 uniqueness guard.
            return;
        }

        ProcessingJob job = existingJob.orElseGet(ProcessingJob::new);
        job.setWorkspace(integration.getWorkspace());
        job.setJobType(ProcessingJobType.RENEW_JIRA_WEBHOOK);
        job.setStatus(ProcessingJobStatus.PENDING);
        job.setPayload(Map.of(
            "workspaceId", integration.getWorkspace().getId().toString(),
            "jiraIntegrationId", integration.getId().toString()
        ));
        job.setAttempts(0);
        job.setLockedAt(null);
        job.setLockedBy(null);
        job.setLastError(null);
        job.setFinishedAt(null);
        job.setAvailableAt(expiresAt.minus(JIRA_WEBHOOK_RENEWAL_LEAD));
        // Flush while the raw callback token is still available so uniqueness or
        // payload constraint failures can immediately compensate the remote hook.
        processingJobRepository.saveAndFlush(job);
    }

    private void registerRollbackCompensation(
            WebhookProvisioning provisioning,
            JiraIntegration integration,
            String accessToken) {
        if (!provisioning.created()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        compensateProvisionedWebhook(
                            provisioning,
                            integration,
                            accessToken
                        );
                    }
                }
            }
        );
    }

    private void compensateProvisionedWebhook(
            WebhookProvisioning provisioning,
            JiraIntegration integration,
            String accessToken) {
        if (!provisioning.created() || !provisioning.claimCompensation()) {
            return;
        }

        boolean deleted = deleteRemoteWebhookBestEffort(
            integration.getCloudId(),
            accessToken,
            provisioning.webhookId(),
            integration.getId()
        );
        if (!deleted) {
            // Allow afterCompletion to retry if an earlier in-method cleanup failed.
            provisioning.releaseCompensation();
        }
    }

    private boolean deleteRemoteWebhookBestEffort(JiraIntegration integration) {
        if (integration.getWebhookId() == null) {
            return true;
        }
        try {
            String accessToken = getValidAccessToken(integration);
            return deleteRemoteWebhookBestEffort(
                integration.getCloudId(),
                accessToken,
                integration.getWebhookId(),
                integration.getId()
            );
        } catch (RuntimeException exception) {
            // Revoking locally must still stop ingestion even when Atlassian is unavailable.
            log.warn(
                "Failed to remove remote Jira webhook during disconnect integrationId={}",
                integration.getId()
            );
            return false;
        }
    }

    private boolean deleteRemoteWebhookBestEffort(
            String cloudId,
            String accessToken,
            long webhookId,
            UUID integrationId) {
        try {
            jiraApiClient.deleteWebhook(cloudId, accessToken, webhookId);
            return true;
        } catch (RuntimeException exception) {
            log.warn(
                "Failed to remove remote Jira webhook integrationId={}",
                integrationId
            );
            return false;
        }
    }

    private static final class WebhookProvisioning {

        private final long webhookId;
        private final boolean created;
        private final AtomicBoolean compensationClaimed = new AtomicBoolean();

        private WebhookProvisioning(long webhookId, boolean created) {
            this.webhookId = webhookId;
            this.created = created;
        }

        private long webhookId() {
            return webhookId;
        }

        private boolean created() {
            return created;
        }

        private boolean claimCompensation() {
            return compensationClaimed.compareAndSet(false, true);
        }

        private void releaseCompensation() {
            compensationClaimed.set(false);
        }
    }

    private void verifyManagerRole(Membership membership) {
        if (membership == null || membership.getRole() != MembershipRole.MANAGER) {
            throw new ApiException(ProblemCode.MANAGER_REQUIRED);
        }
    }

    private JiraIntegrationResponse toResponse(JiraIntegration i, int projectCount) {
        return new JiraIntegrationResponse(
            i.getId(),
            i.getWorkspace().getId(),
            i.getCloudId(),
            i.getSiteUrl(),
            i.getDisplayName(),
            i.getStatus(),
            i.getLastSyncedAt(),
            projectCount
        );
    }

    private JiraProjectResponse toProjectResponse(JiraProject p) {
        return new JiraProjectResponse(
            p.getId(),
            p.getWorkspace().getId(),
            p.getJiraIntegration().getId(),
            p.getJiraProjectId(),
            p.getProjectKey(),
            p.getProjectName(),
            p.getProjectType(),
            p.isTrackingEnabled(),
            p.getLastSyncedAt()
        );
    }
}
