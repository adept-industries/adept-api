package com.adept.api.integration.jira;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.jira.dto.JiraConnectUrlResponse;
import com.adept.api.integration.jira.dto.JiraIntegrationResponse;
import com.adept.api.integration.jira.dto.JiraProjectResponse;
import com.adept.api.integration.jira.dto.UpdateJiraProjectRequest;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;
import com.adept.api.workspace.ActiveMembershipService;
import com.adept.api.workspace.Membership;

import jakarta.validation.Valid;

@Validated
@ConditionalOnProperty(name = "app.jira.enabled", havingValue = "true")
@RestController
public class JiraIntegrationController {

    private final JiraIntegrationService jiraIntegrationService;
    private final CurrentPrincipal currentPrincipal;
    private final ActiveMembershipService activeMembershipService;

    public JiraIntegrationController(
            JiraIntegrationService jiraIntegrationService,
            CurrentPrincipal currentPrincipal,
            ActiveMembershipService activeMembershipService) {
        this.jiraIntegrationService = jiraIntegrationService;
        this.currentPrincipal = currentPrincipal;
        this.activeMembershipService = activeMembershipService;
    }

    @PostMapping("/api/v1/integrations/jira/connect-url")
    public ResponseEntity<JiraConnectUrlResponse> createConnectUrl() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        JiraConnectUrlResponse response = jiraIntegrationService.createConnectUrl(principal.workspaceId(), membership);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/integrations/jira/callback")
    public ResponseEntity<Void> handleCallback(
            @RequestParam(name = "code") String code,
            @RequestParam(name = "state") String state) {
        String redirectUrl = jiraIntegrationService.handleCallback(code, state);
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, redirectUrl)
            .build();
    }

    @GetMapping("/api/v1/integrations/jira")
    public ResponseEntity<JiraIntegrationResponse> getIntegration() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        return jiraIntegrationService.getIntegration(principal.workspaceId())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping("/api/v1/integrations/jira/{integrationId}")
    public ResponseEntity<Void> disconnect(@PathVariable UUID integrationId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        jiraIntegrationService.disconnect(principal.workspaceId(), integrationId, membership);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/integrations/jira/{integrationId}/sync")
    public ResponseEntity<Void> syncProjects(@PathVariable UUID integrationId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService
            .getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        jiraIntegrationService.requestProjectSync(
            principal.workspaceId(),
            integrationId,
            membership
        );
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v1/jira/projects")
    public ResponseEntity<List<JiraProjectResponse>> listProjects() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        return ResponseEntity.ok(jiraIntegrationService.listProjects(principal.workspaceId(), membership));
    }

    @PatchMapping("/api/v1/jira/projects/{projectId}")
    public ResponseEntity<JiraProjectResponse> updateProjectTracking(
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateJiraProjectRequest request) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        return ResponseEntity.ok(jiraIntegrationService.updateProjectTracking(
            principal.workspaceId(),
            projectId,
            request.trackingEnabled(),
            membership
        ));
    }
}
