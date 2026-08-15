package com.adept.api.integration.github;

import java.net.URI;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.dto.GithubConnectUrlResponse;
import com.adept.api.integration.github.dto.GithubIntegrationResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;
import com.adept.api.workspace.ActiveMembershipService;
import com.adept.api.workspace.Membership;

@Validated
@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1/integrations/github")
public class GithubIntegrationController {

    private final GithubIntegrationService githubIntegrationService;
    private final CurrentPrincipal currentPrincipal;
    private final ActiveMembershipService activeMembershipService;

    public GithubIntegrationController(
            GithubIntegrationService githubIntegrationService,
            CurrentPrincipal currentPrincipal,
            ActiveMembershipService activeMembershipService) {
        this.githubIntegrationService = githubIntegrationService;
        this.currentPrincipal = currentPrincipal;
        this.activeMembershipService = activeMembershipService;
    }

    @PostMapping("/connect-url")
    public ResponseEntity<GithubConnectUrlResponse> createConnectUrl() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        GithubConnectUrlResponse response = githubIntegrationService.createConnectUrl(principal.workspaceId(), membership);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> handleCallback(
            @RequestParam(name = "installation_id") long installationId,
            @RequestParam(name = "state") String state) {
        String redirectUrl = githubIntegrationService.handleCallback(installationId, state);
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, redirectUrl)
            .build();
    }

    @GetMapping
    public ResponseEntity<GithubIntegrationResponse> getIntegration() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        return githubIntegrationService.getIntegration(principal.workspaceId())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{integrationId}/sync")
    public ResponseEntity<Void> syncRepositories(@PathVariable UUID integrationId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        githubIntegrationService.syncRepositories(principal.workspaceId(), integrationId, membership);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{integrationId}")
    public ResponseEntity<Void> disconnect(@PathVariable UUID integrationId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        githubIntegrationService.disconnect(principal.workspaceId(), integrationId, membership);
        return ResponseEntity.noContent().build();
    }
}
