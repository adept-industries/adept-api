package com.adept.api.webhook;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;

import jakarta.servlet.http.HttpServletRequest;

/** Public Jira webhook endpoint enabled independently from the GitHub integration. */
@ConditionalOnProperty(name = "app.jira.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1/webhooks/jira")
public class JiraWebhookController {

    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    private final WebhookService webhookService;

    public JiraWebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/{integrationId}")
    public ResponseEntity<Void> receiveJiraWebhook(
            @PathVariable UUID integrationId,
            @RequestParam(name = "token", required = false) String webhookToken,
            HttpServletRequest request) throws IOException {
        byte[] rawBody = readBodyBytes(request);
        Map<String, Object> safeHeaders = collectSafeHeaders(request);

        webhookService.ingestJiraWebhook(integrationId, webhookToken, rawBody, safeHeaders);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .contentType(MediaType.APPLICATION_JSON)
            .build();
    }

    private byte[] readBodyBytes(HttpServletRequest request) throws IOException {
        int contentLength = request.getContentLength();
        if (contentLength > MAX_BODY_BYTES) {
            throw new ApiException(
                ProblemCode.PAYLOAD_TOO_LARGE,
                "Webhook payload exceeds the 10 MiB limit"
            );
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ApiException(
                ProblemCode.PAYLOAD_TOO_LARGE,
                "Webhook payload exceeds the 10 MiB limit"
            );
        }
        if (body.length == 0) {
            throw new ApiException(ProblemCode.MALFORMED_REQUEST, "Webhook body is empty");
        }
        return body;
    }

    private Map<String, Object> collectSafeHeaders(HttpServletRequest request) {
        Map<String, Object> safeHeaders = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement().toLowerCase();
            if (name.equals("authorization") || name.equals("cookie")) {
                continue;
            }
            safeHeaders.put(name, request.getHeader(name));
        }
        return safeHeaders;
    }
}
