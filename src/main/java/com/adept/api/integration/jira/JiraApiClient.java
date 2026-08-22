package com.adept.api.integration.jira;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;

@ConditionalOnProperty(name = "app.jira.enabled", havingValue = "true")
@Service
public class JiraApiClient {

    private static final String ATLASSIAN_API_BASE_URL = "https://api.atlassian.com";
    private static final List<String> ADEPT_WEBHOOK_EVENTS = List.of(
        "jira:issue_created",
        "jira:issue_updated",
        "jira:issue_deleted"
    );

    private final RestClient restClient;

    public JiraApiClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
            .baseUrl(ATLASSIAN_API_BASE_URL)
            .build();
    }

    public List<JiraProjectDetails> listProjects(String cloudId, String accessToken) {
        List<JiraProjectDetails> projects = new ArrayList<>();
        int startAt = 0;
        int maxResults = 50;

        try {
            while (true) {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.get()
                    .uri("/ex/jira/{cloudId}/rest/api/3/project/search?startAt={startAt}&maxResults={maxResults}",
                        cloudId, startAt, maxResults)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);

                if (response == null || !response.containsKey("values")) {
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> values = (List<Map<String, Object>>) response.get("values");
                if (values == null || values.isEmpty()) {
                    break;
                }

                for (Map<String, Object> p : values) {
                    String id = String.valueOf(p.get("id"));
                    String key = (String) p.get("key");
                    String name = (String) p.get("name");
                    String projectTypeKey = (String) p.getOrDefault("projectTypeKey", "software");

                    projects.add(new JiraProjectDetails(id, key, name, projectTypeKey));
                }

                int total = ((Number) response.getOrDefault("total", projects.size())).intValue();
                boolean isLast = Boolean.TRUE.equals(response.get("isLast"));
                if (isLast || projects.size() >= total || values.size() < maxResults) {
                    break;
                }
                startAt += values.size();
            }

            return projects;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "Failed to fetch Jira projects: " + exception.getMessage()
            );
        }
    }

    /** Registers the single dynamic webhook used to ingest Jira issue changes. */
    public long registerWebhook(String cloudId, String accessToken, String callbackUrl) {
        try {
            Map<String, Object> payload = Map.of(
                "url", callbackUrl,
                "webhooks", List.of(Map.of(
                    "events", ADEPT_WEBHOOK_EVENTS,
                    // Jira requires the property; an empty JQL filter matches every issue.
                    "jqlFilter", ""
                ))
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                .uri("/ex/jira/{cloudId}/rest/api/3/webhook", cloudId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Map.class);

            Map<String, Object> result = firstWebhookResult(response);
            Object createdWebhookId = result.get("createdWebhookId");
            if (!(createdWebhookId instanceof Number number)) {
                Object errors = result.get("errors");
                String detail = errors instanceof List<?> list && !list.isEmpty()
                    ? "Atlassian rejected the Jira webhook: " + String.join("; ", list.stream().map(Object::toString).toList())
                    : "Atlassian did not register the Jira webhook";
                throw new ApiException(
                    ProblemCode.INTEGRATION_PROVIDER_ERROR,
                    detail
                );
            }
            return number.longValue();
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw providerFailure("register Jira webhook", exception);
        }
    }

    /** Lists all active dynamic webhook IDs registered by this OAuth app. */
    public List<Long> listWebhookIds(String cloudId, String accessToken) {
        List<Long> ids = new ArrayList<>();
        int startAt = 0;
        for (int pageNumber = 0; pageNumber < 1_000; pageNumber++) {
            try {
                int pageStart = startAt;
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                        .path("/ex/jira/{cloudId}/rest/api/3/webhook")
                        .queryParam("startAt", pageStart)
                        .queryParam("maxResults", 100)
                        .build(cloudId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);

                List<?> values = response == null
                    ? null
                    : response.get("values") instanceof List<?> list ? list : null;
                if (values == null) {
                    break;
                }
                for (Object value : values) {
                    if (value instanceof Map<?, ?> webhook && webhook.get("id") instanceof Number id) {
                        ids.add(id.longValue());
                    }
                }
                if (Boolean.TRUE.equals(response.get("isLast")) || values.isEmpty()) {
                    break;
                }
                int pageSize = response.get("maxResults") instanceof Number number
                    ? number.intValue()
                    : values.size();
                if (pageSize <= 0) {
                    break;
                }
                startAt += pageSize;
            } catch (Exception exception) {
                break;
            }
        }
        return ids;
    }

    /** Deletes all dynamic webhooks registered by this OAuth app. */
    public void deleteAllWebhooks(String cloudId, String accessToken) {
        try {
            List<Long> ids = listWebhookIds(cloudId, accessToken);
            for (Long id : ids) {
                deleteWebhook(cloudId, accessToken, id);
            }
        } catch (Exception ignored) {
            // Best effort cleanup
        }
    }

    /** Returns whether Atlassian still lists the stored dynamic webhook ID. */
    public boolean webhookExists(String cloudId, String accessToken, long webhookId) {
        int startAt = 0;
        for (int pageNumber = 0; pageNumber < 1_000; pageNumber++) {
            try {
                int pageStart = startAt;
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                        .path("/ex/jira/{cloudId}/rest/api/3/webhook")
                        .queryParam("startAt", pageStart)
                        .queryParam("maxResults", 100)
                        .build(cloudId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);

                List<?> values = response == null
                    ? null
                    : response.get("values") instanceof List<?> list ? list : null;
                if (values == null) {
                    throw invalidWebhookListResponse();
                }
                boolean found = values.stream().anyMatch(value ->
                    value instanceof Map<?, ?> webhook
                        && webhook.get("id") instanceof Number id
                        && id.longValue() == webhookId
                );
                if (found) {
                    return true;
                }
                if (Boolean.TRUE.equals(response.get("isLast"))) {
                    return false;
                }

                int pageSize = response.get("maxResults") instanceof Number number
                    ? number.intValue()
                    : values.size();
                if (pageSize <= 0) {
                    throw invalidWebhookListResponse();
                }
                startAt += pageSize;
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                throw providerFailure("list Jira webhooks", exception);
            }
        }
        throw invalidWebhookListResponse();
    }

    /** Extends a dynamic webhook by another 30 days and returns Atlassian's expiry. */
    public Instant refreshWebhook(String cloudId, String accessToken, long webhookId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.put()
                .uri("/ex/jira/{cloudId}/rest/api/3/webhook/refresh", cloudId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("webhookIds", List.of(webhookId)))
                .retrieve()
                .body(Map.class);

            Object expirationDate = response == null ? null : response.get("expirationDate");
            if (!(expirationDate instanceof String value) || value.isBlank()) {
                throw new ApiException(
                    ProblemCode.INTEGRATION_PROVIDER_ERROR,
                    "Atlassian did not return the Jira webhook expiration date"
                );
            }
            return parseAtlassianInstant(value);
        } catch (RestClientResponseException exception) {
            if (isMissingWebhook(exception)) {
                throw new JiraWebhookNotFoundException();
            }
            throw providerFailure("refresh Jira webhook", exception);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw providerFailure("refresh Jira webhook", exception);
        }
    }

    /** Best-effort caller support for disconnecting or replacing a dynamic webhook. */
    public void deleteWebhook(String cloudId, String accessToken, long webhookId) {
        try {
            restClient.method(HttpMethod.DELETE)
                .uri("/ex/jira/{cloudId}/rest/api/3/webhook", cloudId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("webhookIds", List.of(webhookId)))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            // Deletion is idempotent from Adept's perspective.
            if (isMissingWebhook(exception)) {
                return;
            }
            throw providerFailure("delete Jira webhook", exception);
        } catch (Exception exception) {
            throw providerFailure("delete Jira webhook", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstWebhookResult(Map<String, Object> response) {
        Object resultsObject = response == null ? null : response.get("webhookRegistrationResult");
        if (!(resultsObject instanceof List<?> results)
                || results.isEmpty()
                || !(results.getFirst() instanceof Map<?, ?> result)) {
            throw new ApiException(
                ProblemCode.INTEGRATION_PROVIDER_ERROR,
                "Atlassian returned an invalid Jira webhook registration response"
            );
        }
        return (Map<String, Object>) result;
    }

    private ApiException invalidWebhookListResponse() {
        return new ApiException(
            ProblemCode.INTEGRATION_PROVIDER_ERROR,
            "Atlassian returned an invalid Jira webhook list response"
        );
    }

    private Instant parseAtlassianInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ignored) {
            return OffsetDateTime.parse(
                value,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx")
            ).toInstant();
        }
    }

    private ApiException providerFailure(String operation, Exception ignored) {
        return new ApiException(
            ProblemCode.INTEGRATION_PROVIDER_ERROR,
            // Provider failures can echo the registration body. Never propagate
            // their message because that body contains the one-time callback token.
            "Failed to " + operation
        );
    }

    private boolean isMissingWebhook(RestClientResponseException exception) {
        int statusCode = exception.getStatusCode().value();
        return statusCode == 404 || statusCode == 410;
    }

    static final class JiraWebhookNotFoundException extends RuntimeException {
        JiraWebhookNotFoundException() {
            super(null, null, false, false);
        }
    }

    public record JiraProjectDetails(
        String id,
        String key,
        String name,
        String projectType
    ) {
    }
}
