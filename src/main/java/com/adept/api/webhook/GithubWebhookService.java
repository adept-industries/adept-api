package com.adept.api.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.PullRequestState;
import com.adept.api.common.domain.WebhookSource;
import com.adept.api.common.domain.WebhookStatus;
import com.adept.api.config.AppProperties;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.pullrequest.PullRequest;
import com.adept.api.pullrequest.PullRequestRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class GithubWebhookService {

    private final AppProperties appProperties;
    private final RawWebhookEventRepository rawWebhookEventRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GithubWebhookService(
            AppProperties appProperties,
            RawWebhookEventRepository rawWebhookEventRepository,
            GitRepositoryRepository gitRepositoryRepository,
            PullRequestRepository pullRequestRepository,
            ProcessingJobRepository processingJobRepository) {
        this.appProperties = appProperties;
        this.rawWebhookEventRepository = rawWebhookEventRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.processingJobRepository = processingJobRepository;
    }

    public boolean verifySignature(String rawPayload, String signatureHeader) {
        String secret = appProperties.github().webhookSecret();
        if (secret == null || secret.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            return true; // Allow local/dev testing or when secret is unset
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception exc) {
            return false;
        }
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleWebhook(
            String eventType,
            String deliveryId,
            String signatureHeader,
            String rawPayload) throws Exception {

        if (!verifySignature(rawPayload, signatureHeader)) {
            return Map.of("status", "rejected", "reason", "invalid_signature");
        }

        Map<String, Object> payload = objectMapper.readValue(
            rawPayload,
            new TypeReference<Map<String, Object>>() {}
        );

        String effectiveDeliveryId = (deliveryId != null && !deliveryId.isBlank())
            ? deliveryId
            : UUID.randomUUID().toString();

        RawWebhookEvent rawEvent = new RawWebhookEvent();
        rawEvent.setSource(WebhookSource.GITHUB);
        rawEvent.setDeliveryId(effectiveDeliveryId);
        rawEvent.setEventType(eventType != null ? eventType : "unknown");
        rawEvent.setPayload(payload);
        rawEvent.setStatus(WebhookStatus.RECEIVED);

        // Find repository from payload
        Map<String, Object> repoPayload = (Map<String, Object>) payload.get("repository");
        GitRepository repository = null;
        if (repoPayload != null) {
            Number githubRepoIdNum = (Number) repoPayload.get("id");
            String fullName = (String) repoPayload.get("full_name");
            if (githubRepoIdNum != null) {
                repository = gitRepositoryRepository.findFirstByGithubRepoId(githubRepoIdNum.longValue())
                    .orElse(null);
            }
            if (repository == null && fullName != null) {
                repository = gitRepositoryRepository.findFirstByFullName(fullName)
                    .orElse(null);
            }
        }

        if (repository != null) {
            rawEvent.setWorkspace(repository.getWorkspace());
            rawEvent.setRepository(repository);
        }

        rawWebhookEventRepository.save(rawEvent);

        if (repository == null) {
            rawEvent.setStatus(WebhookStatus.IGNORED);
            return Map.of("status", "ignored", "reason", "untracked_repository");
        }

        if ("pull_request".equalsIgnoreCase(eventType)) {
            return handlePullRequestEvent(repository, rawEvent, payload);
        } else if ("pull_request_review".equalsIgnoreCase(eventType)
                || "pull_request_review_comment".equalsIgnoreCase(eventType)
                || "check_run".equalsIgnoreCase(eventType)
                || "workflow_run".equalsIgnoreCase(eventType)) {
            return handlePullRequestRelatedEvent(repository, rawEvent, eventType, payload);
        }

        return Map.of("status", "recorded", "event", eventType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePullRequestEvent(
            GitRepository repository,
            RawWebhookEvent rawEvent,
            Map<String, Object> payload) {

        String action = (String) payload.getOrDefault("action", "synchronize");
        Map<String, Object> prData = (Map<String, Object>) payload.get("pull_request");
        if (prData == null) {
            return Map.of("status", "ignored", "reason", "missing_pr_object");
        }

        Number githubPrIdNum = (Number) prData.get("id");
        long githubPrId = githubPrIdNum != null ? githubPrIdNum.longValue() : 0L;
        Number prNumberNum = (Number) prData.get("number");
        int prNumber = prNumberNum != null ? prNumberNum.intValue() : 0;
        String title = (String) prData.getOrDefault("title", "Pull Request #" + prNumber);
        String stateStr = (String) prData.getOrDefault("state", "open");
        boolean draft = Boolean.TRUE.equals(prData.get("draft"));

        Map<String, Object> user = (Map<String, Object>) prData.get("user");
        String authorLogin = user != null ? (String) user.get("login") : "unknown";

        Map<String, Object> base = (Map<String, Object>) prData.get("base");
        String baseRef = base != null ? (String) base.getOrDefault("ref", "main") : "main";

        Map<String, Object> head = (Map<String, Object>) prData.get("head");
        String headRef = head != null ? (String) head.getOrDefault("ref", "patch") : "patch";
        String headSha = head != null ? (String) head.get("sha") : null;

        Number additionsNum = (Number) prData.getOrDefault("additions", 0);
        Number deletionsNum = (Number) prData.getOrDefault("deletions", 0);
        Number changedFilesNum = (Number) prData.getOrDefault("changed_files", 0);
        Number commitsNum = (Number) prData.getOrDefault("commits", 1);

        String createdAtStr = (String) prData.get("created_at");
        String closedAtStr = (String) prData.get("closed_at");
        String mergedAtStr = (String) prData.get("merged_at");

        Instant openedAt = createdAtStr != null ? Instant.parse(createdAtStr) : Instant.now();
        Instant closedAt = closedAtStr != null ? Instant.parse(closedAtStr) : null;
        Instant mergedAt = mergedAtStr != null ? Instant.parse(mergedAtStr) : null;

        PullRequestState state;
        if (mergedAt != null) {
            state = PullRequestState.MERGED;
        } else if ("closed".equalsIgnoreCase(stateStr)) {
            state = PullRequestState.CLOSED;
        } else {
            state = PullRequestState.OPEN;
        }

        PullRequest pullRequest = pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), prNumber)
            .orElseGet(() -> {
                PullRequest newPr = new PullRequest();
                newPr.setWorkspace(repository.getWorkspace());
                newPr.setRepository(repository);
                newPr.setGithubPrId(githubPrId);
                newPr.setNumber(prNumber);
                return newPr;
            });

        pullRequest.setTitle(title);
        pullRequest.setState(state);
        pullRequest.setDraft(draft);
        pullRequest.setAuthorLogin(authorLogin);
        pullRequest.setBaseRef(baseRef);
        pullRequest.setHeadRef(headRef);
        if (headSha != null) pullRequest.setHeadSha(headSha);
        pullRequest.setAdditions(additionsNum.intValue());
        pullRequest.setDeletions(deletionsNum.intValue());
        pullRequest.setChangedFiles(changedFilesNum.intValue());
        pullRequest.setCommitCount(commitsNum.intValue());
        pullRequest.setOpenedAt(openedAt);
        pullRequest.setClosedAt(closedAt);
        pullRequest.setMergedAt(mergedAt);
        pullRequest.setLastSyncedAt(Instant.now());
        pullRequest.setRawData(prData);

        pullRequestRepository.save(pullRequest);

        // Enqueue EVALUATE_PR_RISK processing job for engine worker
        ProcessingJob job = new ProcessingJob();
        job.setWorkspace(repository.getWorkspace());
        job.setRepository(repository);
        job.setRawEvent(rawEvent);
        job.setJobType(ProcessingJobType.EVALUATE_PR_RISK);
        job.setStatus(ProcessingJobStatus.PENDING);
        job.setPriority(10); // High priority
        job.setAvailableAt(Instant.now());
        job.setPayload(Map.of(
            "repositoryId", repository.getId().toString(),
            "prNumber", prNumber,
            "action", action
        ));
        processingJobRepository.save(job);

        rawEvent.setStatus(WebhookStatus.QUEUED);
        rawWebhookEventRepository.save(rawEvent);

        return Map.of(
            "status", "queued",
            "jobType", "EVALUATE_PR_RISK",
            "repositoryId", repository.getId(),
            "prNumber", prNumber,
            "action", action
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handlePullRequestRelatedEvent(
            GitRepository repository,
            RawWebhookEvent rawEvent,
            String eventType,
            Map<String, Object> payload) {

        Map<String, Object> prData = (Map<String, Object>) payload.get("pull_request");
        int prNumber = 0;
        if (prData != null && prData.get("number") instanceof Number num) {
            prNumber = num.intValue();
        }

        if (prNumber > 0) {
            Optional<PullRequest> prOpt = pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), prNumber);
            if (prOpt.isPresent()) {
                PullRequest pr = prOpt.get();
                Map<String, Object> raw = new HashMap<>(pr.getRawData());
                raw.put("last_" + eventType, payload);
                pr.setRawData(raw);
                pr.setLastSyncedAt(Instant.now());
                pullRequestRepository.save(pr);

                ProcessingJob job = new ProcessingJob();
                job.setWorkspace(repository.getWorkspace());
                job.setRepository(repository);
                job.setRawEvent(rawEvent);
                job.setJobType(ProcessingJobType.EVALUATE_PR_RISK);
                job.setStatus(ProcessingJobStatus.PENDING);
                job.setPriority(10);
                job.setAvailableAt(Instant.now());
                job.setPayload(Map.of(
                    "repositoryId", repository.getId().toString(),
                    "prNumber", prNumber,
                    "event", eventType
                ));
                processingJobRepository.save(job);

                rawEvent.setStatus(WebhookStatus.QUEUED);
                rawWebhookEventRepository.save(rawEvent);

                return Map.of("status", "queued", "jobType", "EVALUATE_PR_RISK", "prNumber", prNumber);
            }
        }

        rawEvent.setStatus(WebhookStatus.PROCESSED);
        rawWebhookEventRepository.save(rawEvent);
        return Map.of("status", "processed", "event", eventType);
    }
}
