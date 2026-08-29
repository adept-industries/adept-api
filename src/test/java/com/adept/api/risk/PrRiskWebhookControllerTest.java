package com.adept.api.risk;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.adept.api.risk.dto.PrRiskBroadcastEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrRiskWebhookControllerTest {

    @Mock
    private PrRiskPredictionService predictionService;

    private PrRiskWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new PrRiskWebhookController(predictionService);
    }

    @Test
    void testHandleGithubWebhookOpenedAction() {
        Map<String, Object> payload = Map.of(
            "action", "opened",
            "pull_request", Map.of(
                "title", "Add payment processing",
                "additions", 200,
                "deletions", 50,
                "changed_files", 5
            )
        );

        PrRiskBroadcastEvent expectedEvent = new PrRiskBroadcastEvent(
            "Add payment processing",
            42,
            "MEDIUM",
            0.42
        );

        when(predictionService.predictAndBroadcast(any())).thenReturn(expectedEvent);

        ResponseEntity<Map<String, Object>> response = controller.handleGithubWebhook("pull_request", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("processed");
        assertThat(response.getBody().get("prTitle")).isEqualTo("Add payment processing");
        assertThat(response.getBody().get("riskScore")).isEqualTo(42);
        assertThat(response.getBody().get("riskLevel")).isEqualTo("MEDIUM");
        assertThat(response.getBody().get("probability")).isEqualTo(0.42);

        verify(predictionService).predictAndBroadcast(payload);
    }

    @Test
    void testHandleGithubWebhookSynchronizeAction() {
        Map<String, Object> payload = Map.of(
            "action", "synchronize",
            "pull_request", Map.of(
                "title", "Update dependencies",
                "additions", 10,
                "deletions", 2,
                "changed_files", 1
            )
        );

        PrRiskBroadcastEvent expectedEvent = new PrRiskBroadcastEvent(
            "Update dependencies",
            5,
            "LOW",
            0.05
        );

        when(predictionService.predictAndBroadcast(any())).thenReturn(expectedEvent);

        ResponseEntity<Map<String, Object>> response = controller.handleGithubWebhook("pull_request", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("processed");
        assertThat(response.getBody().get("riskLevel")).isEqualTo("LOW");
    }

    @Test
    void testHandleGithubWebhookIgnoredAction() {
        Map<String, Object> payload = Map.of(
            "action", "closed",
            "pull_request", Map.of("title", "Closed PR")
        );

        ResponseEntity<Map<String, Object>> response = controller.handleGithubWebhook("pull_request", payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo("ignored");
        assertThat(response.getBody().get("reason")).isEqualTo("event_or_action_not_monitored");
    }
}
