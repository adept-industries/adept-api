package com.adept.api.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.workspace.dto.CurrentWorkspaceResponse;
import com.adept.api.workspace.dto.UpdateWorkspaceRequest;
import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

@Service
@Transactional
public class WorkspaceService {

    private final MembershipRepository membershipRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final AuditService auditService;

    public WorkspaceService(
            MembershipRepository membershipRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            AuditService auditService) {
        this.membershipRepository = membershipRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSummaryResponse> getWorkspaces(AuthenticatedPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ForbiddenException(ProblemCode.NO_ACTIVE_MEMBERSHIP);
        }

        List<Membership> activeMemberships = membershipRepository.findAllActiveWithWorkspaceByUserId(principal.userId());
        return activeMemberships.stream()
            .map(WorkspaceSummaryResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public CurrentWorkspaceResponse getCurrentWorkspace(AuthenticatedPrincipal principal) {
        Membership membership = revalidateActiveMembership(principal);
        return CurrentWorkspaceResponse.from(membership);
    }

    public CurrentWorkspaceResponse updateCurrentWorkspace(
            AuthenticatedPrincipal principal,
            UpdateWorkspaceRequest request,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);

        if (request == null) {
            throw new ForbiddenException(ProblemCode.NO_ACTIVE_MEMBERSHIP);
        }
        request.validate();

        Membership membership = revalidateActiveMembership(principal);
        Workspace workspace = membership.getWorkspace();

        List<String> changedFields = new ArrayList<>();

        if (request.isNamePresent()) {
            String newName = request.getName().trim();
            if (!newName.equals(workspace.getName())) {
                workspace.setName(newName);
                changedFields.add("name");
            }
        }

        if (request.isTimezonePresent()) {
            String newTimezone = request.getTimezone().trim();
            if (!newTimezone.equals(workspace.getTimezone())) {
                workspace.setTimezone(newTimezone);
                changedFields.add("timezone");
            }
        }

        workspace = workspaceRepository.saveAndFlush(workspace);

        if (!changedFields.isEmpty()) {
            auditService.record(
                AuditAction.WORKSPACE_UPDATED,
                membership.getUser(),
                membership,
                workspace,
                "WORKSPACE",
                workspace.getId(),
                Map.of("changedFields", changedFields),
                context != null ? context.ipAddress() : null,
                context != null ? context.userAgent() : null
            );
        }

        return CurrentWorkspaceResponse.from(membership);
    }

    private Membership revalidateActiveMembership(AuthenticatedPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.workspaceId() == null) {
            throw new ForbiddenException(ProblemCode.NO_ACTIVE_MEMBERSHIP);
        }

        return membershipRepository.findActiveByUserIdAndWorkspaceId(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ForbiddenException(ProblemCode.NO_ACTIVE_MEMBERSHIP));
    }
}
