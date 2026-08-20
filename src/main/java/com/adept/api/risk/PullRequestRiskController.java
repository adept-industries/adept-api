package com.adept.api.risk;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.integration.github.GitRepository;
import com.adept.api.risk.dto.DxScoreResponse;
import com.adept.api.risk.dto.PullRequestRiskResponse;
import com.adept.api.risk.dto.PullRequestStaleResponse;
import com.adept.api.risk.dto.RiskModelMetadataResponse;
import com.adept.api.risk.dto.RiskyPullRequestResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;
import com.adept.api.security.RepositoryScopeService;

@Validated
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}")
public class PullRequestRiskController {

    private final PullRequestRiskService pullRequestRiskService;
    private final RepositoryScopeService repositoryScopeService;
    private final CurrentPrincipal currentPrincipal;

    public PullRequestRiskController(
            PullRequestRiskService pullRequestRiskService,
            RepositoryScopeService repositoryScopeService,
            CurrentPrincipal currentPrincipal) {
        this.pullRequestRiskService = pullRequestRiskService;
        this.repositoryScopeService = repositoryScopeService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/pull-requests/{prNumber}/risk")
    public ResponseEntity<PullRequestRiskResponse> getPullRequestRisk(
            @PathVariable UUID repositoryId,
            @PathVariable int prNumber) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        GitRepository repo = repositoryScopeService.requireReadableRepository(principal, repositoryId);
        return ResponseEntity.ok(pullRequestRiskService.getLatestRisk(principal.workspaceId(), repo.getId(), prNumber));
    }

    @GetMapping("/pull-requests/risky")
    public ResponseEntity<List<RiskyPullRequestResponse>> listRiskyPullRequests(
            @PathVariable UUID repositoryId,
            @RequestParam(name = "minLevel", defaultValue = "MEDIUM") String minLevel) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        GitRepository repo = repositoryScopeService.requireReadableRepository(principal, repositoryId);
        return ResponseEntity.ok(pullRequestRiskService.listRiskyPullRequests(principal.workspaceId(), repo.getId(), minLevel));
    }

    @PostMapping("/pull-requests/{prNumber}/risk/recalculate")
    public ResponseEntity<PullRequestRiskResponse> recalculateRisk(
            @PathVariable UUID repositoryId,
            @PathVariable int prNumber) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        GitRepository repo = repositoryScopeService.requireReadableRepository(principal, repositoryId);
        return ResponseEntity.ok(pullRequestRiskService.recalculateRisk(principal.workspaceId(), repo.getId(), prNumber));
    }

    @GetMapping("/pull-requests/{prNumber}/stale")
    public ResponseEntity<PullRequestStaleResponse> checkPullRequestStale(
            @PathVariable UUID repositoryId,
            @PathVariable int prNumber,
            @RequestParam(name = "thresholdHours", required = false) Double thresholdHours) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        GitRepository repo = repositoryScopeService.requireReadableRepository(principal, repositoryId);
        return ResponseEntity.ok(pullRequestRiskService.checkStale(principal.workspaceId(), repo.getId(), prNumber, thresholdHours));
    }

    @GetMapping("/risk-model")
    public ResponseEntity<RiskModelMetadataResponse> getRiskModelMetadata(
            @PathVariable UUID repositoryId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        repositoryScopeService.requireReadableRepository(principal, repositoryId);
        return ResponseEntity.ok(pullRequestRiskService.getLatestModelMetadata());
    }

    @GetMapping("/dx-score")
    public ResponseEntity<DxScoreResponse> getRepositoryDxScore(
            @PathVariable UUID repositoryId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        GitRepository repo = repositoryScopeService.requireReadableRepository(principal, repositoryId);
        return ResponseEntity.ok(pullRequestRiskService.getDxScore(principal.workspaceId(), repo.getId()));
    }
}
