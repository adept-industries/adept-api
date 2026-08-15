package com.adept.api.integration.github;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
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
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.integration.common.IntegrationOauthStateService;
import com.adept.api.integration.github.dto.GithubConnectUrlResponse;
import com.adept.api.integration.github.dto.GithubIntegrationResponse;
import com.adept.api.integration.github.dto.RepositorySettingsDto;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;
import tools.jackson.databind.ObjectMapper;

@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
@Service
public class GithubIntegrationService {

    private final AppProperties properties;
    private final GithubIntegrationRepository githubIntegrationRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final WorkspaceRepository workspaceRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final IntegrationOauthStateService oauthStateService;
    private final GithubApiClient githubApiClient;
    private final GithubAppTokenService githubAppTokenService;
    private final AuditService auditService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public GithubIntegrationService(
            AppProperties properties,
            GithubIntegrationRepository githubIntegrationRepository,
            GitRepositoryRepository gitRepositoryRepository,
            WorkspaceRepository workspaceRepository,
            ProcessingJobRepository processingJobRepository,
            IntegrationOauthStateService oauthStateService,
            GithubApiClient githubApiClient,
            GithubAppTokenService githubAppTokenService,
            AuditService auditService,
            Clock clock,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.githubIntegrationRepository = githubIntegrationRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.processingJobRepository = processingJobRepository;
        this.oauthStateService = oauthStateService;
        this.githubApiClient = githubApiClient;
        this.githubAppTokenService = githubAppTokenService;
        this.auditService = auditService;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GithubConnectUrlResponse createConnectUrl(UUID workspaceId, Membership membership) {
        verifyManagerRole(membership);

        if (!properties.github().enabled()) {
            throw new ApiException(ProblemCode.INTEGRATION_DISABLED, "GitHub integration is not enabled");
        }

        Workspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.WORKSPACE_NOT_FOUND));

        IntegrationOauthStateService.IssuedOauthState issued = oauthStateService.issueState(
            ExternalProvider.GITHUB,
            workspace,
            membership,
            null,
            "/dashboard/integrations"
        );

        String appSlug = properties.github().appSlug();
        String connectUrl = String.format(
            "https://github.com/apps/%s/installations/new?state=%s",
            appSlug,
            issued.rawState()
        );

        return new GithubConnectUrlResponse(connectUrl, issued.rawState());
    }

    @Transactional
    public String handleCallback(long installationId, String rawState) {
        IntegrationOauthStateService.ConsumedOauthState consumedState =
            oauthStateService.consumeState(ExternalProvider.GITHUB, rawState);

        Workspace workspace = consumedState.workspace();
        Membership initiatedBy = consumedState.initiatedBy();

        Optional<GithubIntegration> existingByInstallation =
            githubIntegrationRepository.findByInstallationId(installationId);

        if (existingByInstallation.isPresent()
                && !existingByInstallation.get().getWorkspace().getId().equals(workspace.getId())) {
            throw new ApiException(
                ProblemCode.INTEGRATION_CONFLICT,
                "This GitHub App installation is already connected to another workspace"
            );
        }

        GithubApiClient.GithubInstallationDetails installationDetails =
            githubApiClient.getInstallation(installationId);

        GithubIntegration integration = existingByInstallation.orElseGet(GithubIntegration::new);
        integration.setWorkspace(workspace);
        integration.setInstallationId(installationId);
        integration.setAccountExternalId(installationDetails.accountExternalId());
        integration.setAccountLogin(installationDetails.accountLogin());
        integration.setAccountType(installationDetails.accountType());
        integration.setRepositorySelection(installationDetails.repositorySelection());
        integration.setStatus(IntegrationStatus.ACTIVE);
        integration.setPermissions(installationDetails.permissions());
        integration.setInstalledBy(initiatedBy);
        integration.setInstalledAt(clock.instant());
        integration.setLastSyncedAt(clock.instant());

        githubIntegrationRepository.save(integration);

        // Synchronize repository catalog
        syncRepositoriesInternal(workspace, integration);

        // Record audit log
        auditService.record(
            AuditAction.GITHUB_INTEGRATION_CONNECTED,
            initiatedBy.getUser(),
            initiatedBy,
            workspace,
            "GITHUB_INTEGRATION",
            integration.getId(),
            Map.of(
                "installationId", installationId,
                "accountLogin", installationDetails.accountLogin(),
                "accountType", installationDetails.accountType().name()
            )
        );

        return properties.frontendBaseUrl().resolve("/dashboard/integrations?github=connected").toString();
    }

    @Transactional(readOnly = true)
    public Optional<GithubIntegrationResponse> getIntegration(UUID workspaceId) {
        List<GithubIntegration> integrations = githubIntegrationRepository.findAllByWorkspaceId(workspaceId);
        if (integrations.isEmpty()) {
            return Optional.empty();
        }

        GithubIntegration active = integrations.stream()
            .filter(i -> i.getStatus() == IntegrationStatus.ACTIVE)
            .findFirst()
            .orElse(integrations.get(0));

        int repoCount = gitRepositoryRepository.countByGithubIntegrationId(active.getId());
        return Optional.of(toResponse(active, repoCount));
    }

    @Transactional
    public void disconnect(UUID workspaceId, UUID integrationId, Membership membership) {
        verifyManagerRole(membership);

        GithubIntegration integration = githubIntegrationRepository.findByIdAndWorkspaceId(integrationId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.INTEGRATION_NOT_FOUND));

        integration.setStatus(IntegrationStatus.REVOKED);
        githubIntegrationRepository.save(integration);

        // Disable tracking on all repositories linked to this integration
        List<GitRepository> repositories = gitRepositoryRepository.findAllByGithubIntegrationId(integration.getId());
        for (GitRepository repo : repositories) {
            repo.setTrackingEnabled(false);
            gitRepositoryRepository.save(repo);
        }

        // Evict cached installation token
        githubAppTokenService.evictInstallationToken(integration.getInstallationId());

        // Audit log
        auditService.record(
            AuditAction.GITHUB_INTEGRATION_DISCONNECTED,
            membership.getUser(),
            membership,
            integration.getWorkspace(),
            "GITHUB_INTEGRATION",
            integration.getId(),
            Map.of(
                "installationId", integration.getInstallationId(),
                "accountLogin", integration.getAccountLogin()
            )
        );
    }

    @Transactional
    public void syncRepositories(UUID workspaceId, UUID integrationId, Membership membership) {
        verifyManagerRole(membership);

        GithubIntegration integration = githubIntegrationRepository.findByIdAndWorkspaceId(integrationId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.INTEGRATION_NOT_FOUND));

        if (integration.getStatus() != IntegrationStatus.ACTIVE) {
            throw new ApiException(ProblemCode.INTEGRATION_STATE_INVALID, "Integration is not active");
        }

        syncRepositoriesInternal(integration.getWorkspace(), integration);

        auditService.record(
            AuditAction.GITHUB_REPOSITORIES_SYNCED,
            membership.getUser(),
            membership,
            integration.getWorkspace(),
            "GITHUB_INTEGRATION",
            integration.getId(),
            Map.of("installationId", integration.getInstallationId())
        );
    }

    private void syncRepositoriesInternal(Workspace workspace, GithubIntegration integration) {
        List<GithubApiClient.GithubRepoDetails> remoteRepos =
            githubApiClient.listInstallationRepositories(integration.getInstallationId());

        Set<Long> remoteRepoIds = new HashSet<>();
        Instant now = clock.instant();

        for (GithubApiClient.GithubRepoDetails remote : remoteRepos) {
            remoteRepoIds.add(remote.id());

            Optional<GitRepository> existingOpt =
                gitRepositoryRepository.findByWorkspaceIdAndGithubRepoId(workspace.getId(), remote.id());

            GitRepository repo = existingOpt.orElseGet(GitRepository::new);
            repo.setWorkspace(workspace);
            repo.setGithubIntegration(integration);
            repo.setGithubRepoId(remote.id());
            repo.setGithubNodeId(remote.nodeId());
            repo.setOwnerLogin(remote.ownerLogin());
            repo.setName(remote.name());
            repo.setFullName(remote.fullName());
            repo.setDefaultBranch(remote.defaultBranch());
            repo.setVisibility(remote.visibility());
            repo.setArchived(remote.archived());
            repo.setLastSyncedAt(now);

            if (existingOpt.isEmpty()) {
                repo.setTrackingEnabled(false);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> defaultSettingsMap =
                        objectMapper.convertValue(RepositorySettingsDto.defaults(), Map.class);
                    repo.setSettings(defaultSettingsMap);
                } catch (Exception e) {
                    repo.setSettings(new HashMap<>());
                }
            }

            gitRepositoryRepository.save(repo);
        }

        // Check for repositories removed from GitHub installation
        List<GitRepository> localRepos = gitRepositoryRepository.findAllByGithubIntegrationId(integration.getId());
        for (GitRepository localRepo : localRepos) {
            if (!remoteRepoIds.contains(localRepo.getGithubRepoId())) {
                localRepo.setTrackingEnabled(false);
                localRepo.setArchived(true);
                gitRepositoryRepository.save(localRepo);
            }
        }

        integration.setLastSyncedAt(now);
        githubIntegrationRepository.save(integration);
    }

    private void verifyManagerRole(Membership membership) {
        if (membership == null || membership.getRole() != MembershipRole.MANAGER) {
            throw new ApiException(ProblemCode.MANAGER_REQUIRED);
        }
    }

    private static GithubIntegrationResponse toResponse(GithubIntegration i, int repoCount) {
        return new GithubIntegrationResponse(
            i.getId(),
            i.getWorkspace().getId(),
            i.getInstallationId(),
            i.getAccountLogin(),
            i.getAccountType(),
            i.getRepositorySelection(),
            i.getStatus(),
            i.getInstalledAt(),
            i.getLastSyncedAt(),
            repoCount
        );
    }
}
