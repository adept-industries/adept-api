package com.adept.api.webhook;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

@Hidden
@RestController
@RequestMapping("/api/v1/webhooks/github")
public class GithubWebhookController {

    private final GithubWebhookService githubWebhookService;

    public GithubWebhookController(GithubWebhookService githubWebhookService) {
        this.githubWebhookService = githubWebhookService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> handleGithubWebhook(
            @RequestHeader(name = "X-GitHub-Event", defaultValue = "unknown") String eventType,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signatureHeader,
            @RequestBody String rawPayload) throws Exception {

        Map<String, Object> result = githubWebhookService.handleWebhook(
            eventType,
            deliveryId,
            signatureHeader,
            rawPayload
        );

        if ("rejected".equals(result.get("status"))) {
            return ResponseEntity.status(401).body(result);
        }

        return ResponseEntity.ok(result);
    }
}
