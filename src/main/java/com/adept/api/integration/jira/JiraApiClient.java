package com.adept.api.integration.jira;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;

@ConditionalOnProperty(name = "app.jira.enabled", havingValue = "true")
@Service
public class JiraApiClient {

    private static final String ATLASSIAN_API_BASE_URL = "https://api.atlassian.com";

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

    public record JiraProjectDetails(
        String id,
        String key,
        String name,
        String projectType
    ) {
    }
}
