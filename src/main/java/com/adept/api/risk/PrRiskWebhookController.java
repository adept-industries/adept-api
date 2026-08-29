package com.adept.api.risk;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.risk.dto.PrRiskBroadcastEvent;

import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
@RequestMapping
public class PrRiskWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PrRiskWebhookController.class);

    private final PrRiskPredictionService predictionService;

    public PrRiskWebhookController(PrRiskPredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * Webhook endpoint for GitHub pull_request events.
     * Extracts PR metadata, maps to the 14 tabular features,
     * queries the Python ML microservice on http://localhost:8000/predict,
     * and broadcasts the resulting risk score to connected frontend SSE clients.
     */
    @PostMapping(value = {"/api/webhooks/github", "/api/v1/webhooks/github/predict"})
    public ResponseEntity<Map<String, Object>> handleGithubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventHeader,
            @RequestBody Map<String, Object> payload) {

        String eventType = eventHeader;
        if (eventType == null || eventType.isBlank()) {
            if (payload.containsKey("pull_request")) {
                eventType = "pull_request";
            } else {
                eventType = (String) payload.getOrDefault("event", "unknown");
            }
        }

        String action = (String) payload.getOrDefault("action", "");

        // Process if pull_request event and action is opened or synchronize (or unspecified in testing)
        boolean isPullRequestEvent = "pull_request".equalsIgnoreCase(eventType) || payload.containsKey("pull_request");
        boolean isMonitoredAction = action.isBlank()
            || "opened".equalsIgnoreCase(action)
            || "synchronize".equalsIgnoreCase(action)
            || "reopened".equalsIgnoreCase(action);

        if (isPullRequestEvent && isMonitoredAction) {
            PrRiskBroadcastEvent event = predictionService.predictAndBroadcast(payload);
            return ResponseEntity.ok(Map.of(
                "status", "processed",
                "prTitle", event.prTitle(),
                "riskScore", event.riskScore(),
                "riskLevel", event.riskLevel(),
                "probability", event.probability()
            ));
        }

        log.info("Ignored GitHub webhook delivery: eventType={}, action={}", eventType, action);
        return ResponseEntity.ok(Map.of(
            "status", "ignored",
            "reason", "event_or_action_not_monitored",
            "event", eventType != null ? eventType : "unknown",
            "action", action
        ));
    }
}
