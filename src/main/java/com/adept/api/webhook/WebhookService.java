package com.adept.api.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.WebhookSource;
import com.adept.api.common.domain.WebhookStatus;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.github.GithubIntegration;
import com.adept.api.integration.github.GithubIntegrationRepository;
import com.adept.api.integration.jira.JiraIntegration;
import com.adept.api.integration.jira.JiraIntegrationRepository;
import com.adept.api.integration.jira.JiraProjectRepository;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Handles the secure ingestion of a GitHub webhook delivery:
 * 1. Verifies the HMAC-SHA-256 signature before any DB access.
 * 2. Deduplicates on delivery ID so repeated deliveries are idempotent.
 * 3. Stores raw_webhook_events and processing_jobs in one transaction.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String DUMMY_JIRA_TOKEN_HASH = "0".repeat(64);
    private static final Set<String> GITHUB_INSTALLATION_LIFECYCLE_ACTIONS = Set.of(
        "created",
        "deleted",
        "suspend",
        "unsuspend",
        "new_permissions_accepted"
    );
    private static final Set<String> GITHUB_CATALOG_LIFECYCLE_EVENTS = Set.of(
        "installation",
        "installation_repositories",
        "repository"
    );
    private static final Set<String> JIRA_ISSUE_EVENTS = Set.of(
        "jira:issue_created",
        "jira:issue_updated",
        "jira:issue_deleted"
    );

    private final AppProperties properties;
    private final RawWebhookEventRepository rawWebhookEventRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final GithubIntegrationRepository githubIntegrationRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final JiraIntegrationRepository jiraIntegrationRepository;
    private final JiraProjectRepository jiraProjectRepository;
    private final TokenHasher tokenHasher;
    private final ObjectMapper objectMapper;

    public WebhookService(
            AppProperties properties,
            RawWebhookEventRepository rawWebhookEventRepository,
            ProcessingJobRepository processingJobRepository,
            GithubIntegrationRepository githubIntegrationRepository,
            GitRepositoryRepository gitRepositoryRepository,
            JiraIntegrationRepository jiraIntegrationRepository,
            JiraProjectRepository jiraProjectRepository,
            TokenHasher tokenHasher,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.rawWebhookEventRepository = rawWebhookEventRepository;
        this.processingJobRepository = processingJobRepository;
        this.githubIntegrationRepository = githubIntegrationRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.jiraIntegrationRepository = jiraIntegrationRepository;
        this.jiraProjectRepository = jiraProjectRepository;
        this.tokenHasher = tokenHasher;
        this.objectMapper = objectMapper;
    }

    /**
     * Ingests a verified GitHub webhook delivery.
     *
     * @param rawBody      the raw request bytes; must not be modified before calling this method
     * @param signatureHeader value of the X-Hub-Signature-256 header
     * @param deliveryId   value of the X-GitHub-Delivery header
     * @param eventType    value of the X-GitHub-Event header
     * @param headers      all request headers to persist for debugging
     * @return {@code true} if a new job was created, {@code false} if this delivery was already seen
     */
    @Transactional
    public boolean ingestGithubWebhook(
            byte[] rawBody,
            String signatureHeader,
            String deliveryId,
            String eventType,
            Map<String, Object> headers) {

        // Step 1: Verify HMAC signature before any DB access.
        verifyGithubSignature(rawBody, signatureHeader);

        // Step 2: Deduplicate — return early if we already processed this delivery.
        if (rawWebhookEventRepository.existsBySourceAndDeliveryId(WebhookSource.GITHUB, deliveryId)) {
            log.info("Duplicate GitHub delivery ignored deliveryId={}", deliveryId);
            return false;
        }

        // Step 3: Parse the payload to resolve installation and repository.
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = parsePayload(rawBody);

        String action = (String) payload.get("action");
        Long installationId = extractInstallationId(payload);
        Long githubRepoId = extractRepoId(payload);

        // Step 4: Resolve integration from installation ID (if present).
        GithubIntegration integration = null;
        GitRepository repository = null;

        if (installationId != null) {
            Optional<GithubIntegration> integrationOpt =
                githubIntegrationRepository.findByInstallationId(installationId);

            GithubIntegration resolvedIntegration = integrationOpt.orElse(null);
            boolean active = resolvedIntegration != null
                && resolvedIntegration.getStatus() == IntegrationStatus.ACTIVE;
            boolean suspendedLifecycleEvent = resolvedIntegration != null
                && resolvedIntegration.getStatus() == IntegrationStatus.SUSPENDED
                && "installation".equals(eventType)
                && action != null
                && GITHUB_INSTALLATION_LIFECYCLE_ACTIONS.contains(action);

            if (active || suspendedLifecycleEvent) {
                integration = resolvedIntegration;

                if (githubRepoId != null) {
                    repository = gitRepositoryRepository
                        .findByGithubIntegrationIdAndGithubRepoId(integration.getId(), githubRepoId)
                        .orElse(null);
                }
            } else {
                // Unknown/revoked integrations and ordinary events for a suspended
                // installation remain ignored. Installation lifecycle events must
                // still reach the worker so an unsuspend can reactivate the row.
                log.warn(
                    "GitHub webhook received for unknown/inactive installation={} deliveryId={}",
                    installationId, deliveryId
                );
                RawWebhookEvent ignoredEvent = buildRawEvent(
                    resolvedIntegration != null ? resolvedIntegration.getWorkspace() : null,
                    null,
                    deliveryId,
                    eventType,
                    action,
                    headers,
                    payload,
                    WebhookStatus.IGNORED
                );
                rawWebhookEventRepository.save(ignoredEvent);
                return false;
            }
        }

        // A catalogued repository can remain installed while a manager disables
        // Adept tracking. Keep installation/repository lifecycle events flowing so
        // the local catalog can be repaired or reactivated, but do not create data
        // processing jobs for PR, push, workflow, or deployment traffic.
        if (repository != null
                && !repository.isTrackingEnabled()
                && !GITHUB_CATALOG_LIFECYCLE_EVENTS.contains(eventType)) {
            log.info(
                "GitHub webhook ignored for tracking-disabled repository={} eventType={} deliveryId={}",
                repository.getId(), eventType, deliveryId
            );
            RawWebhookEvent ignoredEvent = buildRawEvent(
                integration.getWorkspace(),
                repository,
                deliveryId,
                eventType,
                action,
                headers,
                payload,
                WebhookStatus.IGNORED
            );
            rawWebhookEventRepository.save(ignoredEvent);
            return false;
        }

        // Step 5: Atomically save the raw event (QUEUED) and its processing job (PENDING).
        RawWebhookEvent event = buildRawEvent(
            integration != null ? integration.getWorkspace() : null,
            repository,
            deliveryId, eventType, action, headers, payload, WebhookStatus.QUEUED
        );
        rawWebhookEventRepository.save(event);

        ProcessingJob job = new ProcessingJob();
        job.setWorkspace(integration != null ? integration.getWorkspace() : null);
        job.setRepository(repository);
        job.setRawEvent(event);
        job.setJobType(ProcessingJobType.PROCESS_GITHUB_EVENT);
        job.setStatus(ProcessingJobStatus.PENDING);
        job.setPayload(Map.of(
            "deliveryId", deliveryId,
            "eventType", eventType,
            "rawEventId", event.getId().toString()
        ));
        job.setAvailableAt(Instant.now());
        processingJobRepository.save(job);

        log.info(
            "GitHub webhook ingested eventType={} deliveryId={} jobId={}",
            eventType, deliveryId, job.getId()
        );
        return true;
    }

    /**
     * Ingests a Jira webhook delivery.
     */
    @Transactional
    public boolean ingestJiraWebhook(
            UUID integrationId,
            String rawWebhookToken,
            byte[] rawBody,
            Map<String, Object> headers) {

        // Step 1: Authenticate the opaque per-integration callback credential before parsing.
        Optional<JiraIntegration> integrationOpt = jiraIntegrationRepository.findById(integrationId);
        String suppliedToken = rawWebhookToken == null ? "" : rawWebhookToken;
        String suppliedHash = tokenHasher.hashJiraWebhookToken(suppliedToken);
        String expectedHash = integrationOpt
            .map(JiraIntegration::getWebhookTokenHash)
            .filter(hash -> hash != null && !hash.isBlank())
            .orElse(DUMMY_JIRA_TOKEN_HASH);
        boolean tokenMatches = MessageDigest.isEqual(
            expectedHash.getBytes(StandardCharsets.US_ASCII),
            suppliedHash.getBytes(StandardCharsets.US_ASCII)
        );
        boolean tokenWellFormed = SecureTokenGenerator.isWellFormed(rawWebhookToken);
        boolean integrationActive = integrationOpt
            .map(integration -> integration.getStatus() == IntegrationStatus.ACTIVE)
            .orElse(false);
        if (!(tokenMatches & tokenWellFormed & integrationActive)) {
            log.warn("Rejected unauthenticated Jira webhook integration={}", integrationId);
            throw new ApiException(
                ProblemCode.WEBHOOK_SIGNATURE_INVALID,
                "Jira webhook token is invalid"
            );
        }
        JiraIntegration integration = integrationOpt.orElseThrow();

        // Step 2: Parse payload to determine event type and delivery ID
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = parsePayload(rawBody);
        String eventType = (String) payload.getOrDefault("webhookEvent", "unknown");

        // The dynamic webhook must subscribe site-wide because Jira does not
        // support Adept's per-project selection directly. Drop unsupported,
        // unknown, and tracking-disabled projects before retaining their issue
        // payload so workspace selection remains a data-retention boundary.
        String jiraProjectId = extractJiraProjectId(payload);
        boolean trackedProject = JIRA_ISSUE_EVENTS.contains(eventType)
            && jiraProjectId != null
            && jiraProjectRepository
                .existsByJiraIntegrationIdAndJiraProjectIdAndTrackingEnabledTrue(
                    integration.getId(),
                    jiraProjectId
                );
        if (!trackedProject) {
            log.info(
                "Jira webhook ignored outside tracked project scope integration={} eventType={}",
                integration.getId(),
                eventType
            );
            return false;
        }

        String deliveryId = jiraDeliveryId(integrationId, rawBody, headers);

        // Step 3: Deduplicate
        if (rawWebhookEventRepository.existsBySourceAndDeliveryId(WebhookSource.JIRA, deliveryId)) {
            log.info("Duplicate Jira delivery ignored deliveryId={}", deliveryId);
            return false;
        }

        // Step 4: Atomically save raw event and job
        RawWebhookEvent event = new RawWebhookEvent();
        event.setWorkspace(integration.getWorkspace());
        event.setSource(WebhookSource.JIRA);
        event.setDeliveryId(deliveryId);
        event.setEventType(eventType);
        event.setHeaders(headers);
        event.setPayload(payload);
        event.setStatus(WebhookStatus.QUEUED);
        event.setSignatureValid(true);
        event.setReceivedAt(Instant.now());
        rawWebhookEventRepository.save(event);

        ProcessingJob job = new ProcessingJob();
        job.setWorkspace(integration.getWorkspace());
        job.setRawEvent(event);
        job.setJobType(ProcessingJobType.PROCESS_JIRA_EVENT);
        job.setStatus(ProcessingJobStatus.PENDING);
        job.setPayload(Map.of(
            "deliveryId", deliveryId,
            "eventType", eventType,
            "rawEventId", event.getId().toString(),
            "jiraIntegrationId", integration.getId().toString(),
            "jiraProjectId", jiraProjectId
        ));
        job.setAvailableAt(Instant.now());
        processingJobRepository.save(job);

        log.info(
            "Jira webhook ingested eventType={} deliveryId={} jobId={}",
            eventType, deliveryId, job.getId()
        );
        return true;
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Verifies the HMAC-SHA-256 signature using a timing-safe byte comparison.
     * Rejects with 401 before any DB access when invalid.
     */
    private void verifyGithubSignature(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            throw new ApiException(ProblemCode.WEBHOOK_SIGNATURE_INVALID,
                "X-Hub-Signature-256 header is missing or malformed");
        }

        String secret = properties.github().webhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new ApiException(ProblemCode.WEBHOOK_SIGNATURE_INVALID,
                "GitHub webhook secret is not configured");
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] expectedBytes = mac.doFinal(rawBody);
            String expected = SIGNATURE_PREFIX + HexFormat.of().formatHex(expectedBytes);

            // Timing-safe comparison — prevents timing attacks that probe signature bytes.
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(ProblemCode.WEBHOOK_SIGNATURE_INVALID,
                    "Webhook signature does not match");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ProblemCode.WEBHOOK_SIGNATURE_INVALID,
                "Signature verification failed");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            throw new ApiException(ProblemCode.MALFORMED_REQUEST,
                "Webhook payload could not be parsed as JSON");
        }
    }

    private String jiraDeliveryId(
            UUID integrationId,
            byte[] rawBody,
            Map<String, Object> headers) {
        Object identifier = headers.get("x-atlassian-webhook-identifier");
        byte[] uniqueInput = identifier == null || identifier.toString().isBlank()
            ? rawBody
            : identifier.toString().getBytes(StandardCharsets.UTF_8);
        byte[] integrationBytes = integrationId.toString().getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        digest.update(integrationBytes);
        digest.update((byte) ':');
        return "jira-" + HexFormat.of().formatHex(digest.digest(uniqueInput));
    }

    private Long extractInstallationId(Map<String, Object> payload) {
        Object installation = payload.get("installation");
        if (installation instanceof Map<?, ?> installationMap) {
            Object id = installationMap.get("id");
            if (id instanceof Number n) {
                return n.longValue();
            }
        }
        return null;
    }

    private Long extractRepoId(Map<String, Object> payload) {
        Object repo = payload.get("repository");
        if (repo instanceof Map<?, ?> repoMap) {
            Object id = repoMap.get("id");
            if (id instanceof Number n) {
                return n.longValue();
            }
        }
        return null;
    }

    private String extractJiraProjectId(Map<String, Object> payload) {
        Object issue = payload.get("issue");
        if (!(issue instanceof Map<?, ?> issueMap)) {
            return null;
        }
        Object fields = issueMap.get("fields");
        if (!(fields instanceof Map<?, ?> fieldsMap)) {
            return null;
        }
        Object project = fieldsMap.get("project");
        if (!(project instanceof Map<?, ?> projectMap)) {
            return null;
        }
        Object projectId = projectMap.get("id");
        return projectId == null ? null : projectId.toString();
    }

    private RawWebhookEvent buildRawEvent(
            com.adept.api.workspace.Workspace workspace,
            GitRepository repository,
            String deliveryId,
            String eventType,
            String action,
            Map<String, Object> headers,
            Map<String, Object> payload,
            WebhookStatus status) {
        RawWebhookEvent event = new RawWebhookEvent();
        event.setWorkspace(workspace);
        event.setRepository(repository);
        event.setSource(WebhookSource.GITHUB);
        event.setDeliveryId(deliveryId);
        event.setEventType(eventType);
        event.setAction(action);
        event.setHeaders(headers);
        event.setPayload(payload);
        event.setStatus(status);
        event.setSignatureValid(true); // Only reaches here if signature was valid
        event.setReceivedAt(Instant.now());
        return event;
    }
}
