package com.adept.api.risk;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import com.adept.api.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PrRiskPredictionServiceTest {

    @Mock
    private AppProperties appProperties;

    @Mock
    private PrRiskSseService sseService;

    private PrRiskPredictionService service;

    @BeforeEach
    void setUp() {
        service = new PrRiskPredictionService(appProperties, RestClient.builder(), sseService);
    }

    @Test
    void testMapGithubPayloadTo14Features() {
        Map<String, Object> payload = Map.of(
            "action", "opened",
            "pull_request", Map.of(
                "title", "Refactor authentication service",
                "additions", 125,
                "deletions", 42,
                "changed_files", 7
            )
        );

        Map<String, Object> features = service.mapGithubPayloadToFeatures(payload);

        assertThat(features).hasSize(14);
        assertThat(features.get("la")).isEqualTo(125.0);
        assertThat(features.get("ld")).isEqualTo(42.0);
        assertThat(features.get("nf")).isEqualTo(7.0);

        // Historical metrics stubbed with 0.0
        assertThat(features.get("ns")).isEqualTo(0.0);
        assertThat(features.get("nd")).isEqualTo(0.0);
        assertThat(features.get("entropy")).isEqualTo(0.0);
        assertThat(features.get("ndev")).isEqualTo(0.0);
        assertThat(features.get("lt")).isEqualTo(0.0);
        assertThat(features.get("nuc")).isEqualTo(0.0);
        assertThat(features.get("age")).isEqualTo(0.0);
        assertThat(features.get("exp")).isEqualTo(0.0);
        assertThat(features.get("rexp")).isEqualTo(0.0);
        assertThat(features.get("sexp")).isEqualTo(0.0);
        assertThat(features.get("fix")).isEqualTo(0.0);
    }

    @Test
    void testExtractPrTitle() {
        Map<String, Object> payload = Map.of(
            "pull_request", Map.of("title", "Fix database connection leak")
        );
        assertThat(service.extractPrTitle(payload)).isEqualTo("Fix database connection leak");

        Map<String, Object> fallbackPayload = Map.of("title", "Fallback Title");
        assertThat(service.extractPrTitle(fallbackPayload)).isEqualTo("Fallback Title");

        Map<String, Object> emptyPayload = Map.of();
        assertThat(service.extractPrTitle(emptyPayload)).isEqualTo("Untitled Pull Request");
    }
}
