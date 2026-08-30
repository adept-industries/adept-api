package com.adept.api.issue;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.issue.dto.ProjectGithubIssuePageResponse;
import com.adept.api.issue.dto.ProjectIssueSyncResponse;
import com.adept.api.issue.dto.ProjectJiraIssuePageResponse;
import com.adept.api.security.CurrentPrincipal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/issues")
public class ProjectIssueController {

    private final ProjectIssueService service;
    private final CurrentPrincipal currentPrincipal;

    public ProjectIssueController(
            ProjectIssueService service,
            CurrentPrincipal currentPrincipal) {
        this.service = service;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping("/github")
    public ResponseEntity<ProjectGithubIssuePageResponse> listGithub(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(service.listGithub(
            currentPrincipal.require(), projectId, page, size
        ));
    }

    @GetMapping("/jira")
    public ResponseEntity<ProjectJiraIssuePageResponse> listJira(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(service.listJira(
            currentPrincipal.require(), projectId, page, size
        ));
    }

    @PostMapping("/sync")
    public ResponseEntity<ProjectIssueSyncResponse> sync(@PathVariable UUID projectId) {
        return ResponseEntity.accepted().body(service.sync(currentPrincipal.require(), projectId));
    }
}
