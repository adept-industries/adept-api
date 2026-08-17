package com.adept.api.integration.github;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.integration.github.dto.LeadCandidateResponse;
import com.adept.api.integration.github.dto.RepositoryResponse;
import com.adept.api.integration.github.dto.UpdateRepositoryRequest;
import com.adept.api.integration.jira.JiraIntegrationService;
import com.adept.api.integration.jira.dto.JiraProjectResponse;
import com.adept.api.integration.jira.dto.MapRepositoryJiraProjectsRequest;
import com.adept.api.invitation.InvitationService;
import com.adept.api.invitation.dto.CreateRepositoryLeadInvitationRequest;
import com.adept.api.invitation.dto.PendingRepositoryLeadInvitationResponse;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;
import com.adept.api.security.RepositoryScopeService;
import com.adept.api.workspace.ActiveMembershipService;
import com.adept.api.workspace.Membership;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@ConditionalOnProperty(name = "app.github.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryService repositoryService;
    private final InvitationService invitationService;
    private final Optional<JiraIntegrationService> jiraIntegrationService;
    private final CurrentPrincipal currentPrincipal;
    private final ActiveMembershipService activeMembershipService;
    private final RepositoryScopeService repositoryScopeService;

    public RepositoryController(
            RepositoryService repositoryService,
            InvitationService invitationService,
            Optional<JiraIntegrationService> jiraIntegrationService,
            CurrentPrincipal currentPrincipal,
            ActiveMembershipService activeMembershipService,
            RepositoryScopeService repositoryScopeService) {
        this.repositoryService = repositoryService;
        this.invitationService = invitationService;
        this.jiraIntegrationService = jiraIntegrationService;
        this.currentPrincipal = currentPrincipal;
        this.activeMembershipService = activeMembershipService;
        this.repositoryScopeService = repositoryScopeService;
    }

    @GetMapping
    public ResponseEntity<List<RepositoryResponse>> list(
            @RequestParam(name = "trackingOnly", required = false) Boolean trackingOnly) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        return ResponseEntity.ok(repositoryService.listRepositories(principal.workspaceId(), membership, trackingOnly));
    }

    @GetMapping("/{repositoryId}")
    public ResponseEntity<RepositoryResponse> get(@PathVariable UUID repositoryId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        GitRepository repository = repositoryScopeService.requireReadableRepository(principal, repositoryId);
        return ResponseEntity.ok(repositoryService.toResponse(repository));
    }

    @PatchMapping("/{repositoryId}")
    public ResponseEntity<RepositoryResponse> update(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody UpdateRepositoryRequest request) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        return ResponseEntity.ok(repositoryService.updateRepository(
            principal.workspaceId(),
            repositoryId,
            request,
            membership
        ));
    }

    @GetMapping("/{repositoryId}/lead-candidates")
    public ResponseEntity<List<LeadCandidateResponse>> getLeadCandidates(@PathVariable UUID repositoryId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        return ResponseEntity.ok(repositoryService.getLeadCandidates(principal.workspaceId(), repositoryId, membership));
    }

    @PostMapping("/{repositoryId}/lead-assignments")
    public ResponseEntity<PendingRepositoryLeadInvitationResponse> createPendingRepositoryLeadInvitation(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody CreateRepositoryLeadInvitationRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        return ResponseEntity.ok(invitationService.createPendingRepositoryLeadInvitation(
            principal.workspaceId(),
            repositoryId,
            membership,
            request,
            com.adept.api.auth.AccountRequestContext.from(servletRequest)
        ));
    }

    @DeleteMapping("/{repositoryId}/lead-assignments/{assignmentId}")
    public ResponseEntity<Void> deleteLeadAssignment(
            @PathVariable UUID repositoryId,
            @PathVariable UUID assignmentId,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        invitationService.deleteLeadAssignment(
            principal.workspaceId(),
            repositoryId,
            assignmentId,
            membership,
            com.adept.api.auth.AccountRequestContext.from(servletRequest)
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{repositoryId}/jira-projects")
    public ResponseEntity<Void> mapJiraProjects(
            @PathVariable UUID repositoryId,
            @Valid @RequestBody MapRepositoryJiraProjectsRequest request) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        Membership membership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));

        JiraIntegrationService jiraService = jiraIntegrationService
            .orElseThrow(() -> new ApiException(ProblemCode.INTEGRATION_DISABLED));
        jiraService.mapProjectsToRepository(
            principal.workspaceId(),
            repositoryId,
            request.jiraProjectIds(),
            membership
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{repositoryId}/jira-projects")
    public ResponseEntity<List<JiraProjectResponse>> getMappedJiraProjects(@PathVariable UUID repositoryId) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        repositoryScopeService.requireReadableRepository(principal, repositoryId);

        JiraIntegrationService jiraService = jiraIntegrationService
            .orElseThrow(() -> new ApiException(ProblemCode.INTEGRATION_DISABLED));
        return ResponseEntity.ok(
            jiraService.getMappedProjectsForRepository(principal.workspaceId(), repositoryId)
        );
    }
}
