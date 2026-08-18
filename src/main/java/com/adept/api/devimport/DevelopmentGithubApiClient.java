package com.adept.api.devimport;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Profile("local")
@Service
class DevelopmentGithubApiClient {

    private static final String GITHUB_API_BASE_URL = "https://api.github.com";

    private final RestClient restClient;
    private final String token;

    DevelopmentGithubApiClient(RestClient.Builder restClientBuilder, Environment environment) {
        this.token = firstNonBlank(
            environment.getProperty("GITHUB_TOKEN"),
            environment.getProperty("GH_TOKEN")
        );
        this.restClient = restClientBuilder
            .baseUrl(GITHUB_API_BASE_URL)
            .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()))
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader(HttpHeaders.USER_AGENT, "adept-local-github-importer")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .build();
    }

    Map<String, Object> getRepository(String owner, String repo) {
        return getMap("/repos/{owner}/{repo}", owner, repo);
    }

    List<Map<String, Object>> listPullRequests(String owner, String repo, int maxPullRequests) {
        return getPagedList(
            "/repos/{owner}/{repo}/pulls?state=all&sort=updated&direction=desc&per_page={perPage}&page={page}",
            maxPullRequests,
            owner,
            repo
        );
    }

    Map<String, Object> getPullRequest(String owner, String repo, int number) {
        return getMap("/repos/{owner}/{repo}/pulls/{number}", owner, repo, number);
    }

    List<Map<String, Object>> listPullRequestCommits(String owner, String repo, int number) {
        return getPagedList(
            "/repos/{owner}/{repo}/pulls/{number}/commits?per_page={perPage}&page={page}",
            500,
            owner,
            repo,
            number
        );
    }

    List<Map<String, Object>> listPullRequestReviews(String owner, String repo, int number) {
        return getPagedList(
            "/repos/{owner}/{repo}/pulls/{number}/reviews?per_page={perPage}&page={page}",
            500,
            owner,
            repo,
            number
        );
    }

    List<Map<String, Object>> listPullRequestFiles(String owner, String repo, int number) {
        return getPagedList(
            "/repos/{owner}/{repo}/pulls/{number}/files?per_page={perPage}&page={page}",
            1000,
            owner,
            repo,
            number
        );
    }

    List<Map<String, Object>> listIssueComments(String owner, String repo, int number) {
        return getPagedList(
            "/repos/{owner}/{repo}/issues/{number}/comments?per_page={perPage}&page={page}",
            500,
            owner,
            repo,
            number
        );
    }

    List<Map<String, Object>> listContributors(String owner, String repo) {
        return getPagedListAllowingNonArrayResponse(
            "/repos/{owner}/{repo}/contributors?per_page={perPage}&page={page}",
            100,
            owner,
            repo
        );
    }

    List<Map<String, Object>> listWorkflowRuns(String owner, String repo, int maxRuns) {
        List<Map<String, Object>> workflowRuns = new ArrayList<>();
        int page = 1;

        while (workflowRuns.size() < maxRuns) {
            int perPage = Math.min(100, maxRuns - workflowRuns.size());
            Map<String, Object> response = getMap(
                "/repos/{owner}/{repo}/actions/runs?per_page={perPage}&page={page}",
                owner,
                repo,
                perPage,
                page
            );
            List<Map<String, Object>> rows = listValue(response, "workflow_runs");
            if (rows.isEmpty()) {
                break;
            }
            rows.stream()
                .limit((long) maxRuns - workflowRuns.size())
                .forEach(workflowRuns::add);
            if (rows.size() < perPage) {
                break;
            }
            page++;
        }

        return workflowRuns;
    }

    private List<Map<String, Object>> getPagedList(
            String uriTemplate,
            int maxItems,
            Object... fixedUriVariables) {
        return getPagedList(uriTemplate, maxItems, false, fixedUriVariables);
    }

    private List<Map<String, Object>> getPagedListAllowingNonArrayResponse(
            String uriTemplate,
            int maxItems,
            Object... fixedUriVariables) {
        return getPagedList(uriTemplate, maxItems, true, fixedUriVariables);
    }

    private List<Map<String, Object>> getPagedList(
            String uriTemplate,
            int maxItems,
            boolean allowNonArrayResponse,
            Object... fixedUriVariables) {
        List<Map<String, Object>> items = new ArrayList<>();
        int page = 1;

        while (items.size() < maxItems) {
            int perPage = Math.min(100, maxItems - items.size());
            Object[] uriVariables = appendPaginationVariables(fixedUriVariables, perPage, page);
            List<Map<String, Object>> rows = getList(uriTemplate, allowNonArrayResponse, uriVariables);
            if (rows.isEmpty()) {
                break;
            }
            rows.stream()
                .limit((long) maxItems - items.size())
                .forEach(items::add);
            if (rows.size() < perPage) {
                break;
            }
            page++;
        }

        return items;
    }

    private Map<String, Object> getMap(String uriTemplate, Object... uriVariables) {
        try {
            Map<String, Object> body = authenticatedGet(uriTemplate, uriVariables)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
            if (body == null) {
                throw new IllegalStateException("GitHub returned an empty response.");
            }
            return body;
        } catch (RestClientResponseException exception) {
            throw providerException(exception);
        }
    }

    private List<Map<String, Object>> getList(String uriTemplate, Object... uriVariables) {
        return getList(uriTemplate, false, uriVariables);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(
            String uriTemplate,
            boolean allowNonArrayResponse,
            Object... uriVariables) {
        try {
            Object body = authenticatedGet(uriTemplate, uriVariables)
                .retrieve()
                .body(Object.class);
            if (body == null) {
                return List.of();
            }
            if (body instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            if (allowNonArrayResponse) {
                return List.of();
            }
            throw new IllegalStateException("GitHub returned " + body.getClass().getSimpleName()
                + " where an array response was expected.");
        } catch (RestClientResponseException exception) {
            throw providerException(exception);
        }
    }

    private RestClient.RequestHeadersSpec<?> authenticatedGet(String uriTemplate, Object... uriVariables) {
        RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uriTemplate, uriVariables);
        if (!token.isBlank()) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request;
    }

    private IllegalStateException providerException(RestClientResponseException exception) {
        String message = "GitHub API request failed with status " + exception.getStatusCode().value();
        if (exception.getStatusCode().value() == 403 && token.isBlank()) {
            message += ". Set GITHUB_TOKEN to avoid the low anonymous GitHub API rate limit.";
        }
        return new IllegalStateException(message, exception);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private static Object[] appendPaginationVariables(Object[] fixedUriVariables, int perPage, int page) {
        Object[] result = new Object[fixedUriVariables.length + 2];
        System.arraycopy(fixedUriVariables, 0, result, 0, fixedUriVariables.length);
        result[result.length - 2] = perPage;
        result[result.length - 1] = page;
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
