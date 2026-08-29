package com.adept.api.risk;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.adept.api.config.AppProperties;
import com.adept.api.risk.dto.PrRiskBroadcastEvent;

@Service
public class PrRiskPredictionService {

    private static final Logger log = LoggerFactory.getLogger(PrRiskPredictionService.class);

    private final RestClient restClient;
    private final PrRiskSseService sseService;
    private final String pythonServiceUrl;

    public PrRiskPredictionService(
            AppProperties appProperties,
            RestClient.Builder restClientBuilder,
            PrRiskSseService sseService) {
        this.sseService = sseService;
        String baseUrl = "http://localhost:8000";
        if (appProperties != null && appProperties.engine() != null && appProperties.engine().baseUrl() != null) {
            baseUrl = appProperties.engine().baseUrl().toString();
        }
        this.pythonServiceUrl = baseUrl;
        this.restClient = restClientBuilder
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .baseUrl(baseUrl)
            .build();
    }

    /**
     * Extracts PR title from GitHub payload.
     */
    public String extractPrTitle(Map<String, Object> payload) {
        if (payload != null) {
            if (payload.get("pull_request") instanceof Map<?, ?> prMap) {
                Object title = prMap.get("title");
                if (title != null && !title.toString().isBlank()) {
                    return title.toString();
                }
            }
            Object title = payload.get("title");
            if (title != null && !title.toString().isBlank()) {
                return title.toString();
            }
        }
        return "Untitled Pull Request";
    }

    /**
     * Maps the GitHub webhook payload to the 14 numerical features expected by the Python model:
     * - 'la' mapped to additions
     * - 'ld' mapped to deletions
     * - 'nf' mapped to changed_files
     * - Historical/deep-git metrics stubbed with 0.0 safe defaults.
     */
    public Map<String, Object> mapGithubPayloadToFeatures(Map<String, Object> payload) {
        double additions = 0.0;
        double deletions = 0.0;
        double changedFiles = 0.0;

        if (payload != null) {
            if (payload.get("features") instanceof Map<?, ?> customFeatures) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : customFeatures.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() instanceof Number n) {
                        result.put(entry.getKey().toString(), n.doubleValue());
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }

            if (payload.get("pull_request") instanceof Map<?, ?> prMap) {
                if (prMap.get("additions") instanceof Number n) {
                    additions = n.doubleValue();
                }
                if (prMap.get("deletions") instanceof Number n) {
                    deletions = n.doubleValue();
                }
                if (prMap.get("changed_files") instanceof Number n) {
                    changedFiles = n.doubleValue();
                }
            } else {
                if (payload.get("additions") instanceof Number n) {
                    additions = n.doubleValue();
                }
                if (payload.get("deletions") instanceof Number n) {
                    deletions = n.doubleValue();
                }
                if (payload.get("changed_files") instanceof Number n) {
                    changedFiles = n.doubleValue();
                }
            }
        }

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("la", additions);
        features.put("ld", deletions);
        features.put("nf", changedFiles);
        features.put("ns", 0.0);
        features.put("nd", 0.0);
        features.put("entropy", 0.0);
        features.put("ndev", 0.0);
        features.put("lt", 0.0);
        features.put("nuc", 0.0);
        features.put("age", 0.0);
        features.put("exp", 0.0);
        features.put("rexp", 0.0);
        features.put("sexp", 0.0);
        features.put("fix", 0.0);

        return features;
    }

    /**
     * Makes a synchronous HTTP POST request to http://localhost:8000/predict with the 14 features
     * and broadcasts the resulting score to connected frontend SSE clients.
     */
    @SuppressWarnings("unchecked")
    public PrRiskBroadcastEvent predictAndBroadcast(Map<String, Object> payload) {
        String prTitle = extractPrTitle(payload);
        Map<String, Object> features = mapGithubPayloadToFeatures(payload);

        log.info("Invoking Python PR Risk microservice at {}/predict for PR: '{}', additions={}, deletions={}, files={}",
            pythonServiceUrl, prTitle, features.get("la"), features.get("ld"), features.get("nf"));

        Map<String, Object> response = restClient.post()
            .uri("/predict")
            .contentType(MediaType.APPLICATION_JSON)
            .body(features)
            .retrieve()
            .body(Map.class);

        double probability = 0.0;
        int riskScore = 0;
        String riskLevel = "LOW";

        if (response != null) {
            if (response.get("probability") instanceof Number p) {
                probability = p.doubleValue();
            }
            if (response.get("riskScore") instanceof Number s) {
                riskScore = s.intValue();
            }
            if (response.get("riskLevel") instanceof String l) {
                riskLevel = l;
            }
        }

        PrRiskBroadcastEvent event = new PrRiskBroadcastEvent(prTitle, riskScore, riskLevel, probability);
        sseService.broadcast(event);
        return event;
    }
}
