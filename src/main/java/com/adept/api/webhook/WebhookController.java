package com.adept.api.webhook;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Public endpoint that receives GitHub App webhook deliveries.
 *
 * <p>No JWT or CSRF protection — GitHub cannot send either. Signature
 * verification inside {@link WebhookService} is the only trust boundary.
 * The endpoint reads raw bytes before any framework body parsing so the
 * exact bytes are available for HMAC verification.
 */
@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    // GitHub recommends a maximum payload size of 25 MB; we enforce 10 MB to be safe.
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Receives a GitHub webhook delivery.
     * <ol>
     *   <li>Reads required headers.</li>
     *   <li>Reads the raw body (required for HMAC).</li>
     *   <li>Delegates to {@link WebhookService} which verifies, deduplicates, and stores.</li>
     *   <li>Returns {@code 202 Accepted} quickly; background worker processes the job.</li>
     * </ol>
     */
    @PostMapping("/github")
    public ResponseEntity<Void> receiveGithubWebhook(HttpServletRequest request) throws IOException {
        // Read required headers first — reject early if they are missing.
        String signature = request.getHeader("X-Hub-Signature-256");
        String deliveryId = request.getHeader("X-GitHub-Delivery");
        String eventType = request.getHeader("X-GitHub-Event");

        if (deliveryId == null || deliveryId.isBlank()) {
            log.warn("GitHub webhook missing X-GitHub-Delivery header");
            throw new ApiException(ProblemCode.MALFORMED_REQUEST,
                "X-GitHub-Delivery header is required");
        }
        if (eventType == null || eventType.isBlank()) {
            log.warn("GitHub webhook missing X-GitHub-Event header");
            throw new ApiException(ProblemCode.MALFORMED_REQUEST,
                "X-GitHub-Event header is required");
        }

        // Read the raw body. HMAC verification requires the exact bytes.
        byte[] rawBody = readBodyBytes(request);

        // Collect headers to persist for debugging (exclude sensitive values).
        Map<String, Object> safeHeaders = collectSafeHeaders(request);

        webhookService.ingestGithubWebhook(rawBody, signature, deliveryId, eventType, safeHeaders);

        // 202 Accepted: the event is stored; processing continues in the background.
        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).build();
    }

    /**
     * Receives a Jira webhook delivery.
     * The integrationId acts as a secure, unguessable token since Jira does not support HMAC.
     */
    @PostMapping("/jira/{integrationId}")
    public ResponseEntity<Void> receiveJiraWebhook(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID integrationId,
            HttpServletRequest request) throws IOException {
        
        byte[] rawBody = readBodyBytes(request);
        Map<String, Object> safeHeaders = collectSafeHeaders(request);

        webhookService.ingestJiraWebhook(integrationId, rawBody, safeHeaders);

        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).build();
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private byte[] readBodyBytes(HttpServletRequest request) throws IOException {
        int contentLength = request.getContentLength();
        if (contentLength > MAX_BODY_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,
                "GitHub webhook payload exceeds the 10 MiB limit");
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ApiException(ProblemCode.PAYLOAD_TOO_LARGE,
                "GitHub webhook payload exceeds the 10 MiB limit");
        }
        if (body.length == 0) {
            throw new ApiException(ProblemCode.MALFORMED_REQUEST,
                "GitHub webhook body is empty");
        }
        return body;
    }

    private Map<String, Object> collectSafeHeaders(HttpServletRequest request) {
        Map<String, Object> safeHeaders = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement().toLowerCase();
            // Never persist Authorization or cookie headers.
            if (name.equals("authorization") || name.equals("cookie")) {
                continue;
            }
            safeHeaders.put(name, request.getHeader(name));
        }
        return safeHeaders;
    }
}
