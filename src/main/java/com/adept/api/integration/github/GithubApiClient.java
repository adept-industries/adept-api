package com.adept.api.integration.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.adept.api.common.domain.GithubAccountType;
import com.adept.api.common.domain.RepositorySelection;
import com.adept.api.common.domain.RepositoryVisibility;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;

@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
@Service
public class GithubApiClient {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";

    private final GithubAppTokenService tokenService;
    private final RestClient restClient;

    public GithubApiClient(GithubAppTokenService tokenService, RestClient.Builder restClientBuilder) {
        this.tokenService = tokenService;
        this.restClient = restClientBuilder
            .baseUrl(GITHUB_API_BASE_URL)
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    }

    public GithubInstallationDetails getInstallation(long installationId) {
        String appJwt = tokenService.generateAppJwt();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                .uri("/app/installations/{installation_id}", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt)
                .retrieve()
                .body(Map.class);

            if (response == null) {
                throw new ApiException(ProblemCode.INTEGRATION_PROVIDER_ERROR, "Failed to retrieve GitHub installation");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> account = (Map<String, Object>) response.get("account");
            long accountExternalId = ((Number) account.get("id")).longValue();
            String accountLogin = (String) account.get("login");
            String typeStr = (String) account.get("type");
            GithubAccountType accountType = "Organization".equalsIgnoreCase(typeStr)
                ? GithubAccountType.ORGANIZATION
                : GithubAccountType.USER;

            String repoSelectionStr = (String) response.get("repository_selection");
            RepositorySelection selection = "all".equalsIgnoreCase(repoSelectionStr)
                ? RepositorySelection.ALL
                : RepositorySelection.SELECTED;

            @SuppressWarnings("unchecked")
            Map<String, Object> permissions = (Map<String, Object>) response.getOrDefault("permissions", Map.of());

            return new GithubInstallationDetails(
                installationId,
                accountExternalId,
                accountLogin,
                accountType,
                selection,
                permissions
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "GitHub API error fetching installation: " + exception.getMessage()
            );
        }
    }

    public List<GithubRepoDetails> listInstallationRepositories(long installationId) {
        String installationToken = tokenService.getInstallationToken(installationId);
        List<GithubRepoDetails> repositories = new ArrayList<>();
        int page = 1;
        int perPage = 100;

        try {
            while (true) {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.get()
                    .uri("/installation/repositories?per_page={perPage}&page={page}", perPage, page)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                    .retrieve()
                    .body(Map.class);

                if (response == null || !response.containsKey("repositories")) {
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> repos = (List<Map<String, Object>>) response.get("repositories");
                if (repos == null || repos.isEmpty()) {
                    break;
                }

                for (Map<String, Object> repo : repos) {
                    long id = ((Number) repo.get("id")).longValue();
                    String nodeId = (String) repo.get("node_id");
                    String name = (String) repo.get("name");
                    String fullName = (String) repo.get("full_name");
                    String defaultBranch = (String) repo.getOrDefault("default_branch", "main");
                    boolean isPrivate = Boolean.TRUE.equals(repo.get("private"));
                    boolean isArchived = Boolean.TRUE.equals(repo.get("archived"));

                    @SuppressWarnings("unchecked")
                    Map<String, Object> owner = (Map<String, Object>) repo.get("owner");
                    String ownerLogin = owner != null ? (String) owner.get("login") : "";

                    RepositoryVisibility visibility = isPrivate
                        ? RepositoryVisibility.PRIVATE
                        : RepositoryVisibility.PUBLIC;

                    repositories.add(new GithubRepoDetails(
                        id,
                        nodeId,
                        ownerLogin,
                        name,
                        fullName,
                        defaultBranch,
                        visibility,
                        isArchived
                    ));
                }

                int totalCount = ((Number) response.getOrDefault("total_count", repositories.size())).intValue();
                if (repositories.size() >= totalCount || repos.size() < perPage) {
                    break;
                }
                page++;
            }

            return repositories;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "GitHub API error fetching repositories: " + exception.getMessage()
            );
        }
    }

    public List<GithubLeadCandidate> listLeadCandidates(long installationId, String owner, String repo) {
        String installationToken = tokenService.getInstallationToken(installationId);
        List<GithubLeadCandidate> candidates = new ArrayList<>();

        try {
            // First attempt: collaborators endpoint
            try {
                List<Map<String, Object>> collaborators = restClient.get()
                    .uri("/repos/{owner}/{repo}/collaborators?per_page=100", owner, repo)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});

                if (collaborators != null && !collaborators.isEmpty()) {
                    for (Map<String, Object> c : collaborators) {
                        String id = String.valueOf(c.get("id"));
                        String login = (String) c.get("login");
                        String avatarUrl = (String) c.get("avatar_url");
                        String email = c.get("email") instanceof String em && !em.isBlank()
                            ? em
                            : fetchUserPublicEmail(installationToken, login);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perms = (Map<String, Object>) c.get("permissions");
                        String permission = "READ";
                        if (perms != null) {
                            if (Boolean.TRUE.equals(perms.get("admin"))) {
                                permission = "ADMIN";
                            } else if (Boolean.TRUE.equals(perms.get("maintain"))) {
                                permission = "MAINTAIN";
                            } else if (Boolean.TRUE.equals(perms.get("push"))) {
                                permission = "WRITE";
                            }
                        }
                        candidates.add(new GithubLeadCandidate(id, login, avatarUrl, permission, email));
                    }
                    return candidates;
                }
            } catch (Exception ignored) {
                // Collaborators endpoint might be restricted; fallback to contributors
            }

            // Fallback: contributors endpoint
            List<Map<String, Object>> contributors = restClient.get()
                .uri("/repos/{owner}/{repo}/contributors?per_page=100", owner, repo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});

            if (contributors != null) {
                for (Map<String, Object> c : contributors) {
                    String id = String.valueOf(c.get("id"));
                    String login = (String) c.get("login");
                    String avatarUrl = (String) c.get("avatar_url");
                    String email = c.get("email") instanceof String em && !em.isBlank()
                        ? em
                        : fetchUserPublicEmail(installationToken, login);
                    candidates.add(new GithubLeadCandidate(id, login, avatarUrl, "CONTRIBUTOR", email));
                }
            }

            return candidates;
        } catch (Exception exception) {
            // Return empty list if candidate lookup fails, rather than crashing
            return candidates;
        }
    }

    private String fetchUserPublicEmail(String installationToken, String login) {
        if (login == null || login.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> user = restClient.get()
                .uri("/users/{username}", login)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (user != null && user.get("email") instanceof String email && !email.isBlank()) {
                return email.trim();
            }
        } catch (Exception ignored) {
            // Fall back to null if user profile email is private or unavailable
        }
        return null;
    }

    public List<Map<String, Object>> listPullRequests(long installationId, String owner, String repo) {
        String installationToken = tokenService.getInstallationToken(installationId);
        try {
            List<Map<String, Object>> prs = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls?state=all&per_page=30&sort=updated&direction=desc", owner, repo)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            return prs != null ? prs : List.of();
        } catch (Exception exception) {
            return List.of();
        }
    }

    public record GithubInstallationDetails(
        long installationId,
        long accountExternalId,
        String accountLogin,
        GithubAccountType accountType,
        RepositorySelection repositorySelection,
        Map<String, Object> permissions
    ) {
    }

    public record GithubRepoDetails(
        long id,
        String nodeId,
        String ownerLogin,
        String name,
        String fullName,
        String defaultBranch,
        RepositoryVisibility visibility,
        boolean archived
    ) {
    }

    public record GithubLeadCandidate(
        String githubUserId,
        String login,
        String avatarUrl,
        String permission,
        String publicEmail
    ) {
    }
}
