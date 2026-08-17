package com.adept.api.workspace;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.domain.ProcessingJobStatus;
import com.adept.api.common.domain.ProcessingJobType;
import com.adept.api.common.domain.UserStatus;
import com.adept.api.common.domain.WorkspaceStatus;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ConflictException;
import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.NotFoundException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.config.AppProperties;
import com.adept.api.integration.github.GithubIntegration;
import com.adept.api.integration.github.GithubIntegrationRepository;
import com.adept.api.integration.github.RepositoryLeadAssignment;
import com.adept.api.integration.github.RepositoryLeadAssignmentRepository;
import com.adept.api.integration.jira.JiraIntegration;
import com.adept.api.integration.jira.JiraIntegrationRepository;
import com.adept.api.job.ProcessingJob;
import com.adept.api.job.ProcessingJobRepository;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.ratelimit.AuthRateLimiter;
import com.adept.api.user.User;
import com.adept.api.user.UserRepository;
import com.adept.api.workspace.dto.CurrentWorkspaceResponse;
import com.adept.api.workspace.dto.CurrentWorkspaceMemberLookupResponse;
import com.adept.api.workspace.dto.CreateWorkspaceRequest;
import com.adept.api.workspace.dto.DeleteWorkspaceRequest;
import com.adept.api.workspace.dto.LookupWorkspaceMemberRequest;
import com.adept.api.workspace.dto.UpdateWorkspaceRequest;
import com.adept.api.workspace.dto.WorkspaceDeletionResponse;
import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

@Service
@Transactional
public class WorkspaceService {

    private final MembershipRepository membershipRepository;
    private final WorkspaceRepository workspaceRepository;
    private final UserRepository userRepository;
    private final GithubIntegrationRepository githubIntegrationRepository;
    private final JiraIntegrationRepository jiraIntegrationRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final RepositoryLeadAssignmentRepository leadAssignmentRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final AuthRateLimiter authRateLimiter;
    private final AuditService auditService;
    private final WorkspaceSlugService workspaceSlugService;
    private final Clock clock;
    private final AppProperties appProperties;

    public WorkspaceService(
            MembershipRepository membershipRepository,
            WorkspaceRepository workspaceRepository,
            UserRepository userRepository,
            GithubIntegrationRepository githubIntegrationRepository,
            JiraIntegrationRepository jiraIntegrationRepository,
            ProcessingJobRepository processingJobRepository,
            RepositoryLeadAssignmentRepository leadAssignmentRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            AuthRateLimiter authRateLimiter,
            AuditService auditService,
            WorkspaceSlugService workspaceSlugService,
            Clock clock,
            AppProperties appProperties) {
        this.membershipRepository = membershipRepository;
        this.workspaceRepository = workspaceRepository;
        this.userRepository = userRepository;
        this.githubIntegrationRepository = githubIntegrationRepository;
        this.jiraIntegrationRepository = jiraIntegrationRepository;
        this.processingJobRepository = processingJobRepository;
        this.leadAssignmentRepository = leadAssignmentRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.authRateLimiter = authRateLimiter;
        this.auditService = auditService;
        this.workspaceSlugService = workspaceSlugService;
        this.clock = clock;
        this.appProperties = appProperties;
    }

    public void removeMember(
            AuthenticatedPrincipal principal,
            UUID membershipId,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);
        Membership managerMembership = revalidateCurrentManager(principal);
        UUID workspaceId = managerMembership.getWorkspace().getId();

        Membership targetMembership = membershipRepository.findById(membershipId)
            .filter(m -> m.getWorkspace().getId().equals(workspaceId))
            .orElseThrow(() -> new NotFoundException(ProblemCode.WORKSPACE_NOT_FOUND));

        if (targetMembership.getId().equals(managerMembership.getId())) {
            throw new ConflictException(ProblemCode.WORKSPACE_CONFLICT, "Cannot remove yourself from the workspace.");
        }

        if (targetMembership.getRole() == MembershipRole.LEAD) {
            List<RepositoryLeadAssignment> activeAssignments =
                leadAssignmentRepository.findAllByLeadMembershipId(membershipId);
            if (!activeAssignments.isEmpty()) {
                throw new ConflictException(
                    ProblemCode.WORKSPACE_CONFLICT,
                    "Cannot remove a Lead membership with active repository assignments. Unassign the lead from all repositories first."
                );
            }
        }

        membershipRepository.delete(targetMembership);
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

    @Transactional(readOnly = true)
    public CurrentWorkspaceMemberLookupResponse lookupCurrentWorkspaceMember(
            AuthenticatedPrincipal principal,
            LookupWorkspaceMemberRequest request) {
        Membership managerMembership = revalidateCurrentManager(principal);
        String normalizedEmail = normalizeLookupEmail(request);

        return userRepository.findByEmailIgnoreCase(normalizedEmail)
            .map(user -> {
                Membership workspaceMembership = membershipRepository
                    .findByWorkspaceIdAndUserId(managerMembership.getWorkspace().getId(), user.getId())
                    .orElse(null);
                boolean emailVerified = user.getEmailVerifiedAt() != null;
                boolean assignableAsLead = user.getStatus() == UserStatus.ACTIVE
                    && emailVerified
                    && workspaceMembership != null
                    && workspaceMembership.getStatus() == MembershipStatus.ACTIVE
                    && workspaceMembership.getRole() == MembershipRole.LEAD;

                return new CurrentWorkspaceMemberLookupResponse(
                    normalizedEmail,
                    true,
                    emailVerified,
                    workspaceMembership == null ? null : workspaceMembership.getId(),
                    workspaceMembership == null ? null : workspaceMembership.getRole(),
                    workspaceMembership == null ? null : workspaceMembership.getStatus(),
                    assignableAsLead
                );
            })
            .orElseGet(() -> new CurrentWorkspaceMemberLookupResponse(
                normalizedEmail,
                false,
                false,
                null,
                null,
                null,
                false
            ));
    }

    public WorkspaceSummaryResponse createWorkspace(
            AuthenticatedPrincipal principal,
            CreateWorkspaceRequest request,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);
        Membership currentMembership = revalidateActiveMembership(principal);
        Membership created = createWorkspaceMembership(currentMembership.getUser(), request, context);
        return WorkspaceSummaryResponse.from(created);
    }

    /**
     * Re-establishes a workspace boundary for an authenticated account whose
     * previous workspaces are no longer active. The caller must hold the user
     * lock while this method executes.
     */
    public Membership createInitialWorkspace(
            User user,
            CreateWorkspaceRequest request,
            AccountRequestContext context) {
        if (user == null
                || user.getStatus() != UserStatus.ACTIVE
                || user.getEmailVerifiedAt() == null) {
            throw new UnauthorizedException(ProblemCode.SESSION_INVALID);
        }
        if (!membershipRepository.findAllActiveWithWorkspaceByUserId(user.getId()).isEmpty()) {
            throw new ConflictException(ProblemCode.WORKSPACE_CONFLICT);
        }
        return createWorkspaceMembership(user, request, context);
    }

    private Membership createWorkspaceMembership(
            User user,
            CreateWorkspaceRequest request,
            AccountRequestContext context) {

        Workspace workspace = new Workspace();
        workspace.setName(request.name().trim());
        workspace.setTimezone(request.timezone());
        workspace.setSlug(workspaceSlugService.generate(request.name()));
        workspace.setStatus(WorkspaceStatus.ACTIVE);
        workspace = workspaceRepository.saveAndFlush(workspace);

        Membership membership = new Membership();
        membership.setUser(user);
        membership.setWorkspace(workspace);
        membership.setRole(MembershipRole.MANAGER);
        membership.setStatus(MembershipStatus.ACTIVE);
        membership = membershipRepository.saveAndFlush(membership);

        auditService.record(
            AuditAction.WORKSPACE_CREATED,
            user,
            membership,
            workspace,
            "WORKSPACE",
            workspace.getId(),
            Map.of(),
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );
        return membership;
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

    public WorkspaceDeletionResponse deleteCurrentWorkspace(
            AuthenticatedPrincipal principal,
            DeleteWorkspaceRequest request,
            AccountRequestContext context) {
        workspaceAuthorizationService.requireManager(principal);

        if (principal == null || principal.userId() == null) {
            throw new ForbiddenException(ProblemCode.MANAGER_REQUIRED);
        }

        requireRecentAuthentication(principal);

        authRateLimiter.requireDeletion(principal.userId());

        if (request == null || request.confirmationSlug() == null || request.confirmationSlug().isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Confirmation slug is required.");
        }

        // Strict PESSIMISTIC_WRITE lock order: User -> Membership -> Workspace
        User user = userRepository.findByIdForUpdate(principal.userId())
            .orElseThrow(() -> new UnauthorizedException(ProblemCode.SESSION_INVALID));

        Membership membership = membershipRepository.findByIdForUpdate(principal.membershipId())
            .orElseThrow(() -> new ForbiddenException(ProblemCode.MANAGER_REQUIRED));

        Workspace workspace = workspaceRepository.findByIdForUpdate(principal.workspaceId())
            .orElseThrow(() -> new NotFoundException(ProblemCode.WORKSPACE_NOT_FOUND));

        // Revalidate locked snapshot
        if (user.getStatus() != UserStatus.ACTIVE || user.getEmailVerifiedAt() == null
                || user.getTokenVersion() != principal.tokenVersion()) {
            throw new UnauthorizedException(ProblemCode.SESSION_INVALID);
        }

        if (membership.getStatus() != MembershipStatus.ACTIVE || membership.getRole() != MembershipRole.MANAGER
                || !membership.getUser().getId().equals(user.getId())
                || !membership.getWorkspace().getId().equals(workspace.getId())) {
            throw new ForbiddenException(ProblemCode.MANAGER_REQUIRED);
        }

        if (workspace.getStatus() == WorkspaceStatus.DELETING) {
            throw new ConflictException(ProblemCode.WORKSPACE_DELETION_ALREADY_REQUESTED);
        }

        if (workspace.getStatus() != WorkspaceStatus.ACTIVE) {
            throw new ConflictException(ProblemCode.WORKSPACE_CONFLICT);
        }

        if (!workspace.getSlug().equals(request.confirmationSlug())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Confirmation slug does not match workspace slug.");
        }

        workspace.setStatus(WorkspaceStatus.DELETING);
        workspaceRepository.save(workspace);

        List<GithubIntegration> githubIntegrations = githubIntegrationRepository.findAllByWorkspaceIdForUpdate(workspace.getId());
        for (GithubIntegration gh : githubIntegrations) {
            if (gh.getStatus() == IntegrationStatus.ACTIVE) {
                gh.setStatus(IntegrationStatus.SUSPENDED);
                gh.setSuspendedAt(Instant.now());
            }
        }
        githubIntegrationRepository.saveAll(githubIntegrations);

        List<JiraIntegration> jiraIntegrations = jiraIntegrationRepository.findAllByWorkspaceIdForUpdate(workspace.getId());
        for (JiraIntegration jira : jiraIntegrations) {
            if (jira.getStatus() == IntegrationStatus.ACTIVE) {
                jira.setStatus(IntegrationStatus.SUSPENDED);
            }
        }
        jiraIntegrationRepository.saveAll(jiraIntegrations);

        ProcessingJob job = new ProcessingJob();
        job.setWorkspace(null);
        job.setRepository(null);
        job.setRawEvent(null);
        job.setJobType(ProcessingJobType.DELETE_WORKSPACE);
        job.setPayload(Map.of("workspaceId", workspace.getId().toString()));
        job.setStatus(ProcessingJobStatus.PENDING);
        processingJobRepository.save(job);

        auditService.record(
            AuditAction.WORKSPACE_DELETION_REQUESTED,
            user,
            membership,
            workspace,
            "WORKSPACE",
            workspace.getId(),
            Map.of("workspaceId", workspace.getId().toString()),
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );

        return new WorkspaceDeletionResponse(workspace.getId(), WorkspaceStatus.DELETING);
    }

    private void requireRecentAuthentication(AuthenticatedPrincipal principal) {
        Instant authenticatedAt = principal.authenticatedAt();
        Instant cutoff = clock.instant().minus(appProperties.auth().sensitiveActionMaxAge());
        if (authenticatedAt == null || authenticatedAt.isBefore(cutoff)) {
            throw new ForbiddenException(ProblemCode.REAUTHENTICATION_REQUIRED);
        }
    }

    private Membership revalidateCurrentManager(AuthenticatedPrincipal principal) {
        workspaceAuthorizationService.requireManager(principal);
        Membership membership = revalidateActiveMembership(principal);
        if (membership.getRole() != MembershipRole.MANAGER) {
            throw new ForbiddenException(ProblemCode.MANAGER_REQUIRED);
        }
        return membership;
    }

    private Membership revalidateActiveMembership(AuthenticatedPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.workspaceId() == null) {
            throw new ForbiddenException(ProblemCode.NO_ACTIVE_MEMBERSHIP);
        }

        return membershipRepository.findActiveByUserIdAndWorkspaceId(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ForbiddenException(ProblemCode.NO_ACTIVE_MEMBERSHIP));
    }

    private static String normalizeLookupEmail(LookupWorkspaceMemberRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Email is required.");
        }
        return request.email().trim().toLowerCase(Locale.ROOT);
    }
}
