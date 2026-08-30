package com.adept.api.risk;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.common.domain.RiskLevel;
import com.adept.api.risk.dto.ProjectPullRequestRiskPageResponse;
import com.adept.api.risk.dto.ProjectPullRequestRiskRebuildResponse;
import com.adept.api.security.CurrentPrincipal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/pull-request-risks")
public class ProjectPullRequestRiskController {

    private final ProjectPullRequestRiskService service;
    private final CurrentPrincipal currentPrincipal;

    public ProjectPullRequestRiskController(
            ProjectPullRequestRiskService service,
            CurrentPrincipal currentPrincipal) {
        this.service = service;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    public ResponseEntity<ProjectPullRequestRiskPageResponse> list(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(defaultValue = "false") boolean stalledOnly) {
        return ResponseEntity.ok(service.list(
            currentPrincipal.require(),
            projectId,
            page,
            size,
            riskLevel,
            stalledOnly
        ));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<ProjectPullRequestRiskRebuildResponse> rebuild(
            @PathVariable UUID projectId) {
        return ResponseEntity.accepted().body(service.rebuild(currentPrincipal.require(), projectId));
    }
}
