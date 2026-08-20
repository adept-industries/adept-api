package com.adept.api.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.RiskLevel;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.pullrequest.PullRequest;
import com.adept.api.pullrequest.PullRequestRepository;
import com.adept.api.risk.dto.DxScoreResponse;
import com.adept.api.risk.dto.PullRequestRiskResponse;
import com.adept.api.risk.dto.PullRequestStaleResponse;
import com.adept.api.risk.dto.PullRequestSummaryResponse;
import com.adept.api.risk.dto.RiskModelMetadataResponse;
import com.adept.api.risk.dto.RiskyPullRequestResponse;

@Service
@Transactional(readOnly = true)
public class PullRequestRiskService {

    private final PullRequestRepository pullRequestRepository;
    private final RiskPredictionRepository riskPredictionRepository;
    private final EngineClient engineClient;
    private final com.adept.api.integration.github.GitRepositoryRepository gitRepositoryRepository;
    private final com.adept.api.job.ProcessingJobRepository processingJobRepository;
    private final Optional<com.adept.api.integration.github.GithubApiClient> githubApiClient;

    public PullRequestRiskService(
            PullRequestRepository pullRequestRepository,
            RiskPredictionRepository riskPredictionRepository,
            EngineClient engineClient,
            com.adept.api.integration.github.GitRepositoryRepository gitRepositoryRepository,
            com.adept.api.job.ProcessingJobRepository processingJobRepository,
            Optional<com.adept.api.integration.github.GithubApiClient> githubApiClient) {
        this.pullRequestRepository = pullRequestRepository;
        this.riskPredictionRepository = riskPredictionRepository;
        this.engineClient = engineClient;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.processingJobRepository = processingJobRepository;
        this.githubApiClient = githubApiClient;
    }

    @Transactional
    public List<PullRequestSummaryResponse> syncPullRequestsFromGithub(UUID workspaceId, UUID repositoryId) {
        com.adept.api.integration.github.GitRepository repository = gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
            .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));

        if (githubApiClient.isPresent() && repository.getGithubIntegration() != null
                && repository.getGithubIntegration().getStatus() == com.adept.api.common.domain.IntegrationStatus.ACTIVE) {
            long installationId = repository.getGithubIntegration().getInstallationId();
            String owner = repository.getOwnerLogin();
            String repoName = repository.getName();
            List<Map<String, Object>> prList = githubApiClient.get().listPullRequests(installationId, owner, repoName);

            for (Map<String, Object> prData : prList) {
                Number prNum = (Number) prData.get("number");
                if (prNum == null) continue;
                int number = prNum.intValue();

                Number prIdNum = (Number) prData.get("id");
                long githubPrId = prIdNum != null ? prIdNum.longValue() : 0L;
                String title = (String) prData.getOrDefault("title", "PR #" + number);
                String stateStr = (String) prData.getOrDefault("state", "open");
                boolean draft = Boolean.TRUE.equals(prData.get("draft"));

                @SuppressWarnings("unchecked")
                Map<String, Object> user = (Map<String, Object>) prData.get("user");
                String authorLogin = user != null ? (String) user.get("login") : "unknown";

                @SuppressWarnings("unchecked")
                Map<String, Object> base = (Map<String, Object>) prData.get("base");
                String baseRef = base != null ? (String) base.getOrDefault("ref", "main") : "main";

                @SuppressWarnings("unchecked")
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

                com.adept.api.common.domain.PullRequestState state;
                if (mergedAt != null) {
                    state = com.adept.api.common.domain.PullRequestState.MERGED;
                } else if ("closed".equalsIgnoreCase(stateStr)) {
                    state = com.adept.api.common.domain.PullRequestState.CLOSED;
                } else {
                    state = com.adept.api.common.domain.PullRequestState.OPEN;
                }

                PullRequest pullRequest = pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), number)
                    .orElseGet(() -> {
                        PullRequest newPr = new PullRequest();
                        newPr.setWorkspace(repository.getWorkspace());
                        newPr.setRepository(repository);
                        newPr.setGithubPrId(githubPrId);
                        newPr.setNumber(number);
                        return newPr;
                    });

                pullRequest.setTitle(title);
                pullRequest.setState(state);
                pullRequest.setDraft(draft);
                pullRequest.setAuthorLogin(authorLogin);
                pullRequest.setBaseRef(baseRef);
                pullRequest.setHeadRef(headRef);
                if (headSha != null) pullRequest.setHeadSha(headSha);
                pullRequest.setAdditions(additionsNum != null ? additionsNum.intValue() : 0);
                pullRequest.setDeletions(deletionsNum != null ? deletionsNum.intValue() : 0);
                pullRequest.setChangedFiles(changedFilesNum != null ? changedFilesNum.intValue() : 0);
                pullRequest.setCommitCount(commitsNum != null ? commitsNum.intValue() : 1);
                pullRequest.setOpenedAt(openedAt);
                pullRequest.setClosedAt(closedAt);
                pullRequest.setMergedAt(mergedAt);
                pullRequest.setLastSyncedAt(Instant.now());
                pullRequest.setRawData(prData);

                pullRequestRepository.save(pullRequest);

                // If no risk prediction exists yet, enqueue EVALUATE_PR_RISK job
                boolean hasPred = riskPredictionRepository.findFirstByPullRequestIdOrderByPredictedAtDesc(pullRequest.getId()).isPresent();
                if (!hasPred) {
                    com.adept.api.job.ProcessingJob job = new com.adept.api.job.ProcessingJob();
                    job.setWorkspace(repository.getWorkspace());
                    job.setRepository(repository);
                    job.setJobType(com.adept.api.common.domain.ProcessingJobType.EVALUATE_PR_RISK);
                    job.setStatus(com.adept.api.common.domain.ProcessingJobStatus.PENDING);
                    job.setPriority(10);
                    job.setAvailableAt(Instant.now());
                    job.setPayload(Map.of(
                        "repositoryId", repository.getId().toString(),
                        "prNumber", number,
                        "action", "sync"
                    ));
                    processingJobRepository.save(job);
                }
            }
        }

        return listPullRequestsSummary(workspaceId, repositoryId);
    }

    @Transactional
    public PullRequestRiskResponse getLatestRisk(UUID workspaceId, UUID repositoryId, int prNumber) {
        PullRequest pullRequest = pullRequestRepository.findByRepositoryIdAndNumber(repositoryId, prNumber)
            .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));

        return riskPredictionRepository.findFirstByPullRequestIdOrderByPredictedAtDesc(pullRequest.getId())
            .map(this::toRiskResponse)
            .orElseGet(() -> recalculateRisk(workspaceId, repositoryId, prNumber));
    }

    public List<PullRequestSummaryResponse> listPullRequestsSummary(UUID workspaceId, UUID repositoryId) {
        List<PullRequest> prs = pullRequestRepository.findAllByRepositoryIdOrderByOpenedAtDesc(repositoryId);
        return prs.stream().map(pr -> {
            Optional<RiskPrediction> predOpt = riskPredictionRepository.findFirstByPullRequestIdOrderByPredictedAtDesc(pr.getId());
            BigDecimal riskScore = predOpt.map(RiskPrediction::getRiskScore).orElse(null);
            String level = predOpt.map(p -> p.getRiskLevel().name()).orElse(null);
            List<Map<String, Object>> factors = predOpt.map(RiskPrediction::getTopFactors).orElse(List.of());
            String version = predOpt.map(RiskPrediction::getModelVersion).orElse(null);
            Instant predAt = predOpt.map(RiskPrediction::getPredictedAt).orElse(null);

            boolean isStale = false;
            if (pr.getState() == com.adept.api.common.domain.PullRequestState.OPEN) {
                Instant lastActivity = pr.getLastSyncedAt() != null ? pr.getLastSyncedAt() : pr.getOpenedAt();
                long hours = java.time.Duration.between(lastActivity, Instant.now()).toHours();
                isStale = hours >= 120;
            }

            return new PullRequestSummaryResponse(
                pr.getId(),
                repositoryId,
                pr.getNumber(),
                pr.getTitle(),
                pr.getState().name(),
                pr.isDraft(),
                pr.getAuthorLogin(),
                pr.getAdditions(),
                pr.getDeletions(),
                pr.getChangedFiles(),
                pr.getOpenedAt(),
                pr.getMergedAt(),
                pr.getClosedAt(),
                riskScore,
                level,
                factors,
                version,
                predAt,
                isStale
            );
        }).toList();
    }

    public List<PullRequestRiskResponse> getRecentRiskAlerts(UUID workspaceId, UUID repositoryId) {
        Collection<RiskLevel> levels = List.of(RiskLevel.MEDIUM, RiskLevel.HIGH, RiskLevel.CRITICAL);
        List<RiskPrediction> predictions = riskPredictionRepository.findLatestRiskyByRepository(repositoryId, levels);
        return predictions.stream().map(this::toRiskResponse).toList();
    }

    public List<RiskyPullRequestResponse> listRiskyPullRequests(UUID workspaceId, UUID repositoryId, String minLevel) {
        Collection<RiskLevel> levels = "HIGH".equalsIgnoreCase(minLevel)
            ? List.of(RiskLevel.HIGH, RiskLevel.CRITICAL)
            : List.of(RiskLevel.MEDIUM, RiskLevel.HIGH, RiskLevel.CRITICAL);

        List<RiskPrediction> predictions = riskPredictionRepository.findLatestRiskyByRepository(repositoryId, levels);
        return predictions.stream()
            .map(pred -> new RiskyPullRequestResponse(
                repositoryId,
                pred.getPullRequest().getNumber(),
                pred.getPullRequest().getTitle(),
                pred.getPullRequest().getAuthorLogin(),
                pred.getRiskScore(),
                pred.getRiskLevel().name(),
                pred.getTopFactors(),
                pred.getPredictedAt(),
                pred.getModelVersion()
            ))
            .toList();
    }

    @Transactional
    public PullRequestRiskResponse recalculateRisk(UUID workspaceId, UUID repositoryId, int prNumber) {
        PullRequest pullRequest = pullRequestRepository.findByRepositoryIdAndNumber(repositoryId, prNumber)
            .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));

        try {
            Map<String, Object> engineResult = engineClient.recalculateRisk(repositoryId, prNumber);
            Number prob = (Number) engineResult.get("risk_probability");
            String level = (String) engineResult.get("risk_level");
            String version = (String) engineResult.get("model_version");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> factors = (List<Map<String, Object>>) engineResult.get("top_factors");
            String predAtStr = (String) engineResult.get("predicted_at");
            Instant predAt = predAtStr != null ? Instant.parse(predAtStr) : Instant.now();
            String stage = (String) engineResult.getOrDefault("stage", "live");

            return new PullRequestRiskResponse(
                repositoryId,
                prNumber,
                prob != null ? BigDecimal.valueOf(prob.doubleValue()) : BigDecimal.ZERO,
                level != null ? level : "LOW",
                version != null ? version : "unknown",
                BigDecimal.valueOf(0.30),
                factors != null ? factors : List.of(),
                predAt,
                stage
            );
        } catch (Exception exc) {
            // Fallback to checking existing prediction
            return riskPredictionRepository.findFirstByPullRequestIdOrderByPredictedAtDesc(pullRequest.getId())
                .map(this::toRiskResponse)
                .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));
        }
    }

    public PullRequestStaleResponse checkStale(UUID workspaceId, UUID repositoryId, int prNumber, Double thresholdHours) {
        pullRequestRepository.findByRepositoryIdAndNumber(repositoryId, prNumber)
            .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));

        try {
            Map<String, Object> res = engineClient.checkStale(repositoryId, prNumber, thresholdHours);
            boolean isStale = Boolean.TRUE.equals(res.get("is_stale"));
            boolean isOpen = Boolean.TRUE.equals(res.get("is_open"));
            Number thresh = (Number) res.get("threshold_hours");
            Number hrs = (Number) res.get("hours_since_last_activity");
            String reason = (String) res.get("reason");

            return new PullRequestStaleResponse(
                repositoryId,
                prNumber,
                isStale,
                isOpen,
                thresh != null ? thresh.doubleValue() : 120.0,
                hrs != null ? hrs.doubleValue() : 0.0,
                reason != null ? reason : ""
            );
        } catch (Exception exc) {
            return new PullRequestStaleResponse(
                repositoryId,
                prNumber,
                false,
                true,
                thresholdHours != null ? thresholdHours : 120.0,
                0.0,
                "Engine query unavailable"
            );
        }
    }

    @SuppressWarnings("unchecked")
    public RiskModelMetadataResponse getLatestModelMetadata() {
        try {
            Map<String, Object> meta = engineClient.getLatestModelMetadata();
            String name = (String) meta.get("model_name");
            String version = (String) meta.get("model_version");
            String trainedAtStr = (String) meta.get("trained_at");
            Instant trainedAt = (trainedAtStr != null && !trainedAtStr.isBlank()) ? Instant.parse(trainedAtStr) : Instant.now();
            String schemaVersion = (String) meta.get("feature_schema_version");
            List<String> featureNames = (List<String>) meta.get("feature_names");
            Map<String, Object> thresholds = (Map<String, Object>) meta.get("thresholds");
            Map<String, Object> metrics = (Map<String, Object>) meta.get("metrics");
            boolean isDemo = Boolean.TRUE.equals(meta.get("is_demo"));

            return new RiskModelMetadataResponse(
                name != null ? name : "pr-code-change-risk-xgb",
                version != null ? version : "unknown",
                trainedAt,
                schemaVersion != null ? schemaVersion : "v1",
                featureNames != null ? featureNames : List.of(),
                thresholds != null ? thresholds : Map.of(),
                metrics != null ? metrics : Map.of(),
                isDemo
            );
        } catch (Exception exc) {
            return new RiskModelMetadataResponse(
                "pr-code-change-risk-xgb",
                "unavailable",
                Instant.now(),
                "v1",
                List.of(),
                Map.of("medium", 0.15, "high", 0.30),
                Map.of("error", "Engine unavailable"),
                false
            );
        }
    }

    @SuppressWarnings("unchecked")
    public DxScoreResponse getDxScore(UUID workspaceId, UUID repositoryId) {
        try {
            Map<String, Object> res = engineClient.getDxScore(repositoryId);
            String repoName = (String) res.get("repository_name");
            Number score = (Number) res.get("score");
            Map<String, Double> components = (Map<String, Double>) res.get("components");
            Map<String, Double> weights = (Map<String, Double>) res.get("weights");

            return new DxScoreResponse(
                repositoryId,
                repoName != null ? repoName : repositoryId.toString(),
                score != null ? score.doubleValue() : 100.0,
                components != null ? components : Map.of(),
                weights != null ? weights : Map.of()
            );
        } catch (Exception exc) {
            return new DxScoreResponse(
                repositoryId,
                repositoryId.toString(),
                100.0,
                Map.of(),
                Map.of()
            );
        }
    }

    private PullRequestRiskResponse toRiskResponse(RiskPrediction pred) {
        return new PullRequestRiskResponse(
            pred.getRepository().getId(),
            pred.getPullRequest().getNumber(),
            pred.getRiskScore(),
            pred.getRiskLevel().name(),
            pred.getModelVersion(),
            pred.getThresholdUsed(),
            pred.getTopFactors(),
            pred.getPredictedAt(),
            pred.getStage()
        );
    }
}
