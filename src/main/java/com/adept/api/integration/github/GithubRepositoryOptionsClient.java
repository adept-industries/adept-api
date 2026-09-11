package com.adept.api.integration.github;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.adept.api.integration.github.dto.RepositorySettingsOptionsResponse;
import com.adept.api.integration.github.dto.RepositorySettingsOptionsResponse.SettingsOptions;

/** Read-only metadata discovery; workflow files are never downloaded or executed. */
@Service
@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
public class GithubRepositoryOptionsClient {
    static final int MAX_PAGES = 5;
    private static final int PAGE_SIZE = 100;
    private final GithubAppTokenService tokenService;
    private final RestClient client;

    @Autowired
    public GithubRepositoryOptionsClient(GithubAppTokenService tokenService) {
        this(tokenService, builder().requestFactory(requestFactory()).build());
    }

    GithubRepositoryOptionsClient(GithubAppTokenService tokenService, RestClient client) {
        this.tokenService = tokenService;
        this.client = client;
    }

    static RestClient.Builder builder() {
        return RestClient.builder().baseUrl("https://api.github.com")
            .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(4));
        return factory;
    }

    public RepositorySettingsOptionsResponse discover(long installationId, String owner, String repository) {
        final String token;
        try {
            token = tokenService.getInstallationToken(installationId);
        } catch (RuntimeException exception) {
            // Never return provider bodies, credentials, or exception messages to the browser.
            return RepositorySettingsOptionsResponse.unavailable(
                "GitHub access is unavailable. You can still enter patterns manually.");
        }
        return new RepositorySettingsOptionsResponse(
            fetch(token, owner, repository, "branches", null),
            fetch(token, owner, repository, "actions/workflows", "workflows"),
            fetch(token, owner, repository, "environments", "environments")
        );
    }

    private SettingsOptions fetch(String token, String owner, String repository, String path, String collection) {
        var names = new TreeSet<String>();
        boolean malformed = false;
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                // Construct each URL ourselves; never forward the token to a provider-supplied Link URL.
                ResponseEntity<Object> response = client.get()
                    .uri("/repos/{owner}/{repository}/" + path + "?per_page={size}&page={page}",
                        owner, repository, PAGE_SIZE, page)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().toEntity(Object.class);
                Object body = response.getBody();
                Object items = collection == null ? body
                    : body instanceof Map<?, ?> map ? map.get(collection) : null;
                if (!(items instanceof List<?> rows)) {
                    return partial(names, "GitHub returned an incomplete list. You can enter patterns manually.");
                }
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map && map.get("name") instanceof String name && !name.isBlank()) {
                        names.add(name);
                    } else {
                        malformed = true;
                    }
                }
                String link = response.getHeaders().getFirst(HttpHeaders.LINK);
                boolean more = link != null ? link.contains("rel=\"next\"") : rows.size() == PAGE_SIZE;
                if (link == null && body instanceof Map<?, ?> map && map.get("total_count") instanceof Number total) {
                    more = page * PAGE_SIZE < total.longValue();
                }
                if (!more) {
                    return malformed ? partial(names, "Some GitHub entries could not be read. You can enter patterns manually.")
                        : new SettingsOptions(new ArrayList<>(names), true, null);
                }
            }
            return partial(names, "Showing the first 500 GitHub entries. Enter a missing name or pattern manually.");
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            return partial(names, status == 401 || status == 403 || status == 404
                ? "GitHub access is restricted or rate-limited. Check the app's read permissions, or enter patterns manually."
                : "GitHub options are temporarily unavailable. You can enter patterns manually.");
        } catch (RuntimeException exception) {
            return partial(names, "GitHub options are temporarily unavailable. You can enter patterns manually.");
        }
    }

    private static SettingsOptions partial(TreeSet<String> names, String warning) {
        return new SettingsOptions(new ArrayList<>(names), false, warning);
    }
}
