package com.adept.api.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
import com.adept.api.risk.dto.RiskModelMetadataResponse;
import com.adept.api.risk.dto.RiskyPullRequestResponse;

@Service
@Transactional(readOnly = true)
public class PullRequestRiskService {

    private final PullRequestRepository pullRequestRepository;
    private final RiskPredictionRepository riskPredictionRepository;
    private final EngineClient engineClient;

    public PullRequestRiskService(
            PullRequestRepository pullRequestRepository,
            RiskPredictionRepository riskPredictionRepository,
            EngineClient engineClient) {
        this.pullRequestRepository = pullRequestRepository;
        this.riskPredictionRepository = riskPredictionRepository;
        this.engineClient = engineClient;
    }

    @Transactional
    public PullRequestRiskResponse getLatestRisk(UUID workspaceId, UUID repositoryId, int prNumber) {
        PullRequest pullRequest = pullRequestRepository.findByRepositoryIdAndNumber(repositoryId, prNumber)
            .orElseThrow(() -> new NotFoundException(ProblemCode.REPOSITORY_NOT_FOUND));

        return riskPredictionRepository.findFirstByPullRequestIdOrderByPredictedAtDesc(pullRequest.getId())
            .map(this::toRiskResponse)
            .orElseGet(() -> recalculateRisk(workspaceId, repositoryId, prNumber));
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
