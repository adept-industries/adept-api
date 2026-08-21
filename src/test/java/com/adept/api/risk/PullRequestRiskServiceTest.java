package com.adept.api.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adept.api.common.domain.PullRequestState;
import com.adept.api.common.domain.RiskLevel;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.pullrequest.PullRequest;
import com.adept.api.pullrequest.PullRequestRepository;
import com.adept.api.risk.dto.DxScoreResponse;
import com.adept.api.risk.dto.PullRequestRiskResponse;
import com.adept.api.risk.dto.PullRequestStaleResponse;
import com.adept.api.risk.dto.RiskModelMetadataResponse;
import com.adept.api.risk.dto.RiskyPullRequestResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PullRequestRiskServiceTest {

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private RiskPredictionRepository riskPredictionRepository;

    @Mock
    private EngineClient engineClient;

    @InjectMocks
    private PullRequestRiskService pullRequestRiskService;

    private UUID workspaceId;
    private UUID repositoryId;
    private PullRequest pullRequest;
    private RiskPrediction riskPrediction;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        GitRepository repository = new GitRepository();
        repository.setId(repositoryId);

        pullRequest = new PullRequest();
        pullRequest.setId(UUID.randomUUID());
        pullRequest.setNumber(42);
        pullRequest.setTitle("Add OAuth support");
        pullRequest.setState(PullRequestState.OPEN);
        pullRequest.setAuthorLogin("octocat");
        pullRequest.setRepository(repository);

        riskPrediction = new RiskPrediction();
        riskPrediction.setId(UUID.randomUUID());
        riskPrediction.setRepository(repository);
        riskPrediction.setPullRequest(pullRequest);
        riskPrediction.setRiskScore(new BigDecimal("0.750000"));
        riskPrediction.setRiskLevel(RiskLevel.HIGH);
        riskPrediction.setModelVersion("risk-xgb-prod-v1");
        riskPrediction.setThresholdUsed(new BigDecimal("0.300000"));
        riskPrediction.setTopFactors(List.of(
            Map.of("feature", "hotspot_score", "value", 0.88, "impact", 0.45, "direction", "raises_risk")
        ));
        riskPrediction.setPredictedAt(Instant.now());
        riskPrediction.setStage("live");
    }

    @Test
    void getLatestRiskReturnsPersistedPredictionWhenAvailable() {
        when(pullRequestRepository.findByRepositoryIdAndNumber(repositoryId, 42))
            .thenReturn(Optional.of(pullRequest));
        when(riskPredictionRepository.findFirstByPullRequestIdOrderByPredictedAtDesc(pullRequest.getId()))
            .thenReturn(Optional.of(riskPrediction));

        PullRequestRiskResponse response = pullRequestRiskService.getLatestRisk(workspaceId, repositoryId, 42);

        assertThat(response).isNotNull();
        assertThat(response.prNumber()).isEqualTo(42);
        assertThat(response.riskLevel()).isEqualTo("HIGH");
        assertThat(response.riskProbability()).isEqualTo(new BigDecimal("0.750000"));
        assertThat(response.modelVersion()).isEqualTo("risk-xgb-prod-v1");
        assertThat(response.topFactors()).hasSize(1);
    }

    @Test
    void listRiskyPullRequestsReturnsPredictionsAboveThreshold() {
        when(riskPredictionRepository.findLatestRiskyByRepository(eq(repositoryId), any()))
            .thenReturn(List.of(riskPrediction));

        List<RiskyPullRequestResponse> response = pullRequestRiskService.listRiskyPullRequests(
            workspaceId, repositoryId, "HIGH"
        );

        assertThat(response).hasSize(1);
        assertThat(response.get(0).prNumber()).isEqualTo(42);
        assertThat(response.get(0).riskLevel()).isEqualTo("HIGH");
        assertThat(response.get(0).riskScore()).isEqualTo(new BigDecimal("0.750000"));
    }

    @Test
    void checkStaleCallsEngineAndReturnsResponse() {
        when(pullRequestRepository.findByRepositoryIdAndNumber(repositoryId, 42))
            .thenReturn(Optional.of(pullRequest));
        when(engineClient.checkStale(repositoryId, 42, 120.0))
            .thenReturn(Map.of(
                "is_stale", true,
                "is_open", true,
                "threshold_hours", 120.0,
                "hours_since_last_activity", 150.0,
                "reason", "Open PR inactive"
            ));

        PullRequestStaleResponse response = pullRequestRiskService.checkStale(workspaceId, repositoryId, 42, 120.0);

        assertThat(response.isStale()).isTrue();
        assertThat(response.hoursSinceLastActivity()).isEqualTo(150.0);
    }

    @Test
    void getDxScoreReturnsEngineResult() {
        when(engineClient.getDxScore(repositoryId))
            .thenReturn(Map.of(
                "repository_name", "adept-engine",
                "score", 95.5,
                "components", Map.of("flow", 90.0, "review_wait", 100.0),
                "weights", Map.of("flow", 0.20, "review_wait", 0.25)
            ));

        DxScoreResponse response = pullRequestRiskService.getDxScore(workspaceId, repositoryId);

        assertThat(response.score()).isEqualTo(95.5);
        assertThat(response.repositoryName()).isEqualTo("adept-engine");
        assertThat(response.components()).containsKey("flow");
    }

    @Test
    void getLatestModelMetadataReturnsMetadataFromEngine() {
        when(engineClient.getLatestModelMetadata())
            .thenReturn(Map.of(
                "model_name", "pr-code-change-risk-xgb",
                "model_version", "risk-xgb-prod-2026",
                "trained_at", "2026-08-20T10:00:00Z",
                "feature_schema_version", "v1",
                "feature_names", List.of("lines_added", "files_changed"),
                "thresholds", Map.of("medium", 0.15, "high", 0.30),
                "metrics", Map.of("roc_auc", 0.85, "pr_auc", 0.72),
                "is_demo", false
            ));

        RiskModelMetadataResponse response = pullRequestRiskService.getLatestModelMetadata();

        assertThat(response.modelName()).isEqualTo("pr-code-change-risk-xgb");
        assertThat(response.modelVersion()).isEqualTo("risk-xgb-prod-2026");
        assertThat(response.isDemo()).isFalse();
        assertThat(response.featureNames()).contains("lines_added");
    }
}
