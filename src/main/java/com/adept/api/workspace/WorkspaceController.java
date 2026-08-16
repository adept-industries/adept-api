package com.adept.api.workspace;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adept.api.auth.AccountRequestContext;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.CurrentPrincipal;
import com.adept.api.workspace.dto.CurrentWorkspaceResponse;
import com.adept.api.workspace.dto.CreateWorkspaceRequest;
import com.adept.api.workspace.dto.DeleteWorkspaceRequest;
import com.adept.api.workspace.dto.CurrentWorkspaceMemberLookupResponse;
import com.adept.api.workspace.dto.LookupWorkspaceMemberRequest;
import com.adept.api.workspace.dto.UpdateWorkspaceRequest;
import com.adept.api.workspace.dto.WorkspaceDeletionResponse;
import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final CurrentPrincipal currentPrincipal;

    public WorkspaceController(WorkspaceService workspaceService, CurrentPrincipal currentPrincipal) {
        this.workspaceService = workspaceService;
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    public ResponseEntity<List<WorkspaceSummaryResponse>> getWorkspaces() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        return ResponseEntity.ok(workspaceService.getWorkspaces(principal));
    }

    @PostMapping
    public ResponseEntity<WorkspaceSummaryResponse> createWorkspace(
            @Valid @RequestBody CreateWorkspaceRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        AccountRequestContext context = AccountRequestContext.from(servletRequest);
        return ResponseEntity.status(201).body(workspaceService.createWorkspace(principal, request, context));
    }

    @GetMapping("/current")
    public ResponseEntity<CurrentWorkspaceResponse> getCurrentWorkspace() {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        return ResponseEntity.ok(workspaceService.getCurrentWorkspace(principal));
    }

    @PostMapping("/current/members/lookup")
    public ResponseEntity<CurrentWorkspaceMemberLookupResponse> lookupCurrentWorkspaceMember(
            @Valid @RequestBody LookupWorkspaceMemberRequest request) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        return ResponseEntity.ok(workspaceService.lookupCurrentWorkspaceMember(principal, request));
    }

    @PatchMapping("/current")
    public ResponseEntity<CurrentWorkspaceResponse> updateCurrentWorkspace(
            @Valid @RequestBody UpdateWorkspaceRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        AccountRequestContext context = AccountRequestContext.from(servletRequest);
        return ResponseEntity.ok(workspaceService.updateCurrentWorkspace(principal, request, context));
    }

    @DeleteMapping("/current")
    public ResponseEntity<WorkspaceDeletionResponse> deleteCurrentWorkspace(
            @Valid @RequestBody DeleteWorkspaceRequest request,
            HttpServletRequest servletRequest) {
        AuthenticatedPrincipal principal = currentPrincipal.require();
        AccountRequestContext context = AccountRequestContext.from(servletRequest);
        WorkspaceDeletionResponse response = workspaceService.deleteCurrentWorkspace(principal, request, context);
        return ResponseEntity.accepted().body(response);
    }
}
