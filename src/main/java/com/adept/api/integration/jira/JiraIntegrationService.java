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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.common.domain.ExternalProvider;
import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.IntegrationEncryptionService;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.integration.common.IntegrationOauthStateService;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.jira.dto.JiraConnectUrlResponse;
import com.adept.api.integration.jira.dto.JiraIntegrationResponse;
import com.adept.api.integration.jira.dto.JiraProjectResponse;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

@ConditionalOnProperty(name = "app.jira.enabled", havingValue = "true")
@Service
public class JiraIntegrationService {

    private final AppProperties properties;
    private final JiraIntegrationRepository jiraIntegrationRepository;
    private final JiraProjectRepository jiraProjectRepository;
    private final RepositoryJiraProjectRepository repositoryJiraProjectRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final WorkspaceRepository workspaceRepository;
    private final IntegrationOauthStateService oauthStateService;
    private final JiraOAuthClient jiraOAuthClient;
    private final JiraApiClient jiraApiClient;
    private final IntegrationEncryptionService encryptionService;
    private final SecureTokenGenerator tokenGenerator;
    private final AuditService auditService;
    private final Clock clock;

    public JiraIntegrationService(
            AppProperties properties,
            JiraIntegrationRepository jiraIntegrationRepository,
            JiraProjectRepository jiraProjectRepository,
            RepositoryJiraProjectRepository repositoryJiraProjectRepository,
            GitRepositoryRepository gitRepositoryRepository,
            WorkspaceRepository workspaceRepository,
            IntegrationOauthStateService oauthStateService,
            JiraOAuthClient jiraOAuthClient,
            JiraApiClient jiraApiClient,
            IntegrationEncryptionService encryptionService,
            SecureTokenGenerator tokenGenerator,
            AuditService auditService,
            Clock clock) {
        this.properties = properties;
        this.jiraIntegrationRepository = jiraIntegrationRepository;
        this.jiraProjectRepository = jiraProjectRepository;
        this.repositoryJiraProjectRepository = repositoryJiraProjectRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.oauthStateService = oauthStateService;
        this.jiraOAuthClient = jiraOAuthClient;
        this.jiraApiClient = jiraApiClient;
        this.encryptionService = encryptionService;
        this.tokenGenerator = tokenGenerator;
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

        jiraIntegrationRepository.save(integration);

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
            RepositoryJiraProject link = new RepositoryJiraProject();
            link.setId(new RepositoryJiraProjectId(repository.getId(), jp.getId()));
            link.setRepository(repository);
            link.setJiraProject(jp);
            link.setCreatedAt(clock.instant());
            mappings.add(link);
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

        integration.setStatus(IntegrationStatus.REVOKED);
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
