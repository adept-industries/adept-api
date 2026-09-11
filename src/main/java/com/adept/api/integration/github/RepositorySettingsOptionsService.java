package com.adept.api.integration.github;

import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.dto.RepositorySettingsOptionsResponse;
import com.adept.api.workspace.Membership;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Service
@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
public class RepositorySettingsOptionsService {
    private final GitRepositoryRepository repositories;
    private final GithubRepositoryOptionsClient client;
    private final Cache<CacheKey, RepositorySettingsOptionsResponse> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5)).maximumSize(128).build();

    public RepositorySettingsOptionsService(GitRepositoryRepository repositories, GithubRepositoryOptionsClient client) {
        this.repositories = repositories;
        this.client = client;
    }

    // Intentionally not transactional: do not hold a database connection during GitHub requests.
    public RepositorySettingsOptionsResponse get(UUID workspaceId, UUID repositoryId, Membership membership) {
        if (membership == null || membership.getRole() != MembershipRole.MANAGER
                || membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new ApiException(ProblemCode.MANAGER_REQUIRED);
        }
        GitRepository repository = repositories.findForSettingsDiscovery(repositoryId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.REPOSITORY_NOT_FOUND));
        GithubIntegration integration = repository.getGithubIntegration();
        // Check authorization and the current installation status even for cached results.
        if (integration == null || integration.getStatus() != IntegrationStatus.ACTIVE) {
            return RepositorySettingsOptionsResponse.unavailable(
                "Reconnect GitHub to load options. Existing patterns can still be edited manually.");
        }
        var key = new CacheKey(workspaceId, repositoryId, integration.getId(), integration.getInstallationId(),
            repository.getOwnerLogin(), repository.getName());
        var cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        var result = client.discover(key.installationId(), key.owner(), key.repository());
        // Partial failures are retryable, not cached for five minutes.
        if (result.complete()) {
            cache.put(key, result);
        }
        return result;
    }

    private record CacheKey(UUID workspaceId, UUID repositoryId, UUID integrationId,
                            long installationId, String owner, String repository) {}
}
