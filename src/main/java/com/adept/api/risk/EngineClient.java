package com.adept.api.risk;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.adept.api.config.AppProperties;

@Component
public class EngineClient {

    private final RestClient restClient;

    public EngineClient(AppProperties appProperties, RestClient.Builder restClientBuilder) {
        URI baseUrl = appProperties.engine().baseUrl();
        this.restClient = restClientBuilder
            .baseUrl(baseUrl.toString())
            .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> recalculateRisk(UUID repositoryId, int prNumber) {
        return restClient.post()
            .uri("/v1/repositories/{repoId}/pull-requests/{prNumber}/risk/recalculate", repositoryId, prNumber)
            .retrieve()
            .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getLatestModelMetadata() {
        return restClient.get()
            .uri("/v1/risk/model/latest")
            .retrieve()
            .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDxScore(UUID repositoryId) {
        return restClient.get()
            .uri("/v1/repositories/{repoId}/dx-score", repositoryId)
            .retrieve()
            .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> checkStale(UUID repositoryId, int prNumber, Double thresholdHours) {
        String uri = thresholdHours != null
            ? "/v1/repositories/" + repositoryId + "/pull-requests/" + prNumber + "/stale?threshold_hours=" + thresholdHours
            : "/v1/repositories/" + repositoryId + "/pull-requests/" + prNumber + "/stale";
        return restClient.get()
            .uri(uri)
            .retrieve()
            .body(Map.class);
    }
}
