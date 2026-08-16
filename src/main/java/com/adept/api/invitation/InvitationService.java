package com.adept.api.invitation;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.AccountRequestContext;
import com.adept.api.auth.FreshSessionService;
import com.adept.api.auth.LoginResult;
import com.adept.api.common.domain.InvitationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.domain.UserStatus;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.crypto.PasswordService;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.github.RepositoryLeadAssignment;
import com.adept.api.integration.github.RepositoryLeadAssignmentRepository;
import com.adept.api.invitation.dto.AcceptInvitationRequest;
import com.adept.api.invitation.dto.CreateRepositoryLeadInvitationRequest;
import com.adept.api.invitation.dto.InvitationPreviewResponse;
import com.adept.api.invitation.dto.PendingRepositoryLeadInvitationResponse;
import com.adept.api.mail.InvitationMailRequested;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.user.User;
import com.adept.api.user.UserRepository;
import com.adept.api.workspace.ActiveMembershipService;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.MembershipRepository;

@Service
public class InvitationService {

    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final WorkspaceInvitationRepository invitationRepository;
    private final RepositoryLeadAssignmentRepository leadAssignmentRepository;
    private final GitRepositoryRepository gitRepositoryRepository;
    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final PasswordService passwordService;
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final FreshSessionService freshSessionService;
    private final ActiveMembershipService activeMembershipService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public InvitationService(
            WorkspaceInvitationRepository invitationRepository,
            RepositoryLeadAssignmentRepository leadAssignmentRepository,
            GitRepositoryRepository gitRepositoryRepository,
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            PasswordService passwordService,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            FreshSessionService freshSessionService,
            ActiveMembershipService activeMembershipService,
            AuditService auditService,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.invitationRepository = invitationRepository;
        this.leadAssignmentRepository = leadAssignmentRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordService = passwordService;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.freshSessionService = freshSessionService;
        this.activeMembershipService = activeMembershipService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public PendingRepositoryLeadInvitationResponse createPendingRepositoryLeadInvitation(
            UUID workspaceId,
            UUID repositoryId,
            Membership managerMembership,
            CreateRepositoryLeadInvitationRequest request,
            AccountRequestContext context) {
        verifyCurrentWorkspaceManager(managerMembership, workspaceId);
        String normalizedEmail = normalizeEmail(request);

        GitRepository repository = gitRepositoryRepository.findByIdAndWorkspaceId(repositoryId, workspaceId)
            .orElseThrow(() -> new ApiException(ProblemCode.REPOSITORY_NOT_FOUND));

        userRepository.findByEmailIgnoreCase(normalizedEmail)
            .flatMap(user -> membershipRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId()))
            .ifPresent(membership -> {
                throw new ApiException(
                    ProblemCode.WORKSPACE_CONFLICT,
                    "That email already has a membership in this workspace."
                );
            });

        WorkspaceInvitation invitation = invitationRepository
            .findPendingByWorkspaceIdAndEmailForUpdate(workspaceId, normalizedEmail)
            .orElseGet(() -> createPendingInvitation(repository, managerMembership, normalizedEmail, context));

        RepositoryLeadAssignment assignment = leadAssignmentRepository
            .findByRepositoryIdAndInvitationId(repositoryId, invitation.getId())
            .orElseGet(() -> createLeadInvitationAssignment(repository, invitation, managerMembership));

        auditService.record(
            AuditAction.REPOSITORY_LEAD_ASSIGNED,
            managerMembership.getUser(),
            managerMembership,
            repository.getWorkspace(),
            "REPOSITORY_LEAD_ASSIGNMENT",
            assignment.getId(),
            Map.of(
                "repositoryId", repository.getId().toString(),
                "invitationId", invitation.getId().toString(),
                "email", normalizedEmail
            ),
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );

        return PendingRepositoryLeadInvitationResponse.from(assignment, invitation);
    }

    @Transactional
    public PendingRepositoryLeadInvitationResponse createPendingRepositoryLeadInvitation(
            UUID workspaceId,
            UUID repositoryId,
            Membership managerMembership,
            CreateRepositoryLeadInvitationRequest request) {
        return createPendingRepositoryLeadInvitation(workspaceId, repositoryId, managerMembership, request, null);
    }

    @Transactional(readOnly = true)
    public InvitationPreviewResponse previewInvitation(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invitation token is required.");
        }
        String tokenHash = tokenHasher.hashInvitationToken(rawToken.trim());
        WorkspaceInvitation invitation = invitationRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new ApiException(ProblemCode.INVITATION_NOT_FOUND));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ApiException(ProblemCode.INVITATION_INVALID, "Invitation is no longer pending.");
        }
        if (invitation.getExpiresAt().isBefore(clock.instant())) {
            throw new ApiException(ProblemCode.INVITATION_EXPIRED, "Invitation has expired.");
        }

        List<RepositoryLeadAssignment> assignments = leadAssignmentRepository.findAllByInvitationId(invitation.getId());
        List<String> repositories = assignments.stream()
            .map(a -> a.getRepository().getFullName())
            .sorted()
            .toList();

        boolean existingAccount = userRepository.existsByEmailIgnoreCase(invitation.getEmail());

        return new InvitationPreviewResponse(
            invitation.getEmail(),
            invitation.getWorkspace().getName(),
            invitation.getRole(),
            repositories,
            invitation.getExpiresAt(),
            existingAccount
        );
    }

    @Transactional
    public LoginResult acceptInvitation(
            AcceptInvitationRequest request,
            AuthenticatedPrincipal callerPrincipal,
            AccountRequestContext context) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Invitation token is required.");
        }
        String tokenHash = tokenHasher.hashInvitationToken(request.token().trim());
        WorkspaceInvitation invitation = invitationRepository.findByTokenHashForUpdate(tokenHash)
            .orElseThrow(() -> new ApiException(ProblemCode.INVITATION_NOT_FOUND));

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new ApiException(ProblemCode.INVITATION_INVALID, "Invitation is no longer pending.");
        }
        if (invitation.getExpiresAt().isBefore(clock.instant())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            invitationRepository.saveAndFlush(invitation);
            throw new ApiException(ProblemCode.INVITATION_EXPIRED, "Invitation has expired.");
        }

        String email = invitation.getEmail().toLowerCase(Locale.ROOT);
        Optional<User> existingUserOpt = userRepository.findByEmailIgnoreCase(email);
        User user;

        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            if (callerPrincipal != null) {
                if (!callerPrincipal.userId().equals(user.getId())) {
                    throw new ApiException(ProblemCode.WORKSPACE_FORBIDDEN, "Authenticated user does not match invitation recipient.");
                }
            } else {
                if (request.password() == null || request.password().isBlank()) {
                    throw new ApiException(ProblemCode.VALIDATION_FAILED, "Password is required for existing account sign-in.");
                }
                if (!passwordService.matchesAuthenticationCandidate(request.password(), user.getPasswordHash())) {
                    throw new ApiException(ProblemCode.INVALID_CREDENTIALS);
                }
            }
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new ApiException(ProblemCode.WORKSPACE_FORBIDDEN, "User account is suspended.");
            }
            if (user.getEmailVerifiedAt() == null) {
                user.setEmailVerifiedAt(clock.instant());
                userRepository.save(user);
            }
        } else {
            if (request.displayName() == null || request.displayName().isBlank()) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Display name is required for new account.");
            }
            if (request.password() == null || request.password().isBlank()) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Password is required for new account.");
            }
            String passwordHash = passwordService.encodeNewPassword(request.password());
            user = new User();
            user.setEmail(email);
            user.setDisplayName(request.displayName().trim());
            user.setPasswordHash(passwordHash);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(clock.instant());
            user.setTokenVersion(0);
            user = userRepository.saveAndFlush(user);
        }

        UUID workspaceId = invitation.getWorkspace().getId();
        Membership membership = membershipRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId())
            .orElse(null);

        if (membership == null) {
            membership = new Membership();
            membership.setWorkspace(invitation.getWorkspace());
            membership.setUser(user);
            membership.setRole(MembershipRole.LEAD);
            membership.setStatus(MembershipStatus.ACTIVE);
            membership = membershipRepository.saveAndFlush(membership);
        } else if (membership.getStatus() != MembershipStatus.ACTIVE) {
            membership.setStatus(MembershipStatus.ACTIVE);
            membership = membershipRepository.saveAndFlush(membership);
        }

        List<RepositoryLeadAssignment> pendingAssignments = leadAssignmentRepository.findAllByInvitationId(invitation.getId());
        for (RepositoryLeadAssignment assignment : pendingAssignments) {
            if (leadAssignmentRepository.existsByRepositoryIdAndLeadMembershipId(assignment.getRepository().getId(), membership.getId())) {
                leadAssignmentRepository.delete(assignment);
            } else {
                assignment.setLeadMembership(membership);
                assignment.setInvitation(null);
                leadAssignmentRepository.save(assignment);
            }
        }

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(clock.instant());
        invitationRepository.saveAndFlush(invitation);

        auditService.record(
            AuditAction.INVITATION_ACCEPTED,
            user,
            membership,
            invitation.getWorkspace(),
            "WORKSPACE_INVITATION",
            invitation.getId(),
            Map.of(
                "email", email,
                "role", MembershipRole.LEAD.name()
            ),
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );

        List<Membership> activeMemberships = activeMembershipService.getActiveWorkspaces(user.getId());
        return freshSessionService.issue(user, activeMemberships, workspaceId, context, "INVITATION");
    }

    @Transactional
    public void resendInvitation(
            AuthenticatedPrincipal principal,
            UUID invitationId,
            AccountRequestContext context) {
        Membership managerMembership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));
        verifyCurrentWorkspaceManager(managerMembership, principal.workspaceId());

        WorkspaceInvitation invitation = invitationRepository.findByIdAndWorkspaceIdForUpdate(invitationId, principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.INVITATION_NOT_FOUND));

        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            throw new ApiException(ProblemCode.INVITATION_CONFLICT, "Invitation has already been accepted.");
        }
        if (invitation.getStatus() == InvitationStatus.REVOKED) {
            throw new ApiException(ProblemCode.INVITATION_CONFLICT, "Invitation has been revoked.");
        }

        String rawToken = tokenGenerator.generate();
        invitation.setTokenHash(tokenHasher.hashInvitationToken(rawToken));
        invitation.setExpiresAt(clock.instant().plus(INVITATION_TTL));
        invitation.setStatus(InvitationStatus.PENDING);
        invitation = invitationRepository.saveAndFlush(invitation);

        eventPublisher.publishEvent(new InvitationMailRequested(
            invitation.getId(),
            invitation.getEmail(),
            invitation.getWorkspace().getName(),
            rawToken,
            context != null ? context.traceId() : null
        ));

        auditService.record(
            AuditAction.INVITATION_RESENT,
            managerMembership.getUser(),
            managerMembership,
            invitation.getWorkspace(),
            "WORKSPACE_INVITATION",
            invitation.getId(),
            Map.of("email", invitation.getEmail()),
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );
    }

    @Transactional
    public void revokeInvitation(
            AuthenticatedPrincipal principal,
            UUID invitationId,
            AccountRequestContext context) {
        Membership managerMembership = activeMembershipService.getActiveMembership(principal.userId(), principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.NO_ACTIVE_MEMBERSHIP));
        verifyCurrentWorkspaceManager(managerMembership, principal.workspaceId());

        WorkspaceInvitation invitation = invitationRepository.findByIdAndWorkspaceIdForUpdate(invitationId, principal.workspaceId())
            .orElseThrow(() -> new ApiException(ProblemCode.INVITATION_NOT_FOUND));

        if (invitation.getStatus() == InvitationStatus.ACCEPTED) {
            throw new ApiException(ProblemCode.INVITATION_CONFLICT, "Cannot revoke an already accepted invitation.");
        }
        if (invitation.getStatus() == InvitationStatus.REVOKED) {
            return;
        }

        invitation.setStatus(InvitationStatus.REVOKED);
        invitation.setRevokedAt(clock.instant());
        invitationRepository.saveAndFlush(invitation);

        List<RepositoryLeadAssignment> assignments = leadAssignmentRepository.findAllByInvitationId(invitation.getId());
        leadAssignmentRepository.deleteAll(assignments);

        auditService.record(
            AuditAction.INVITATION_REVOKED,
            managerMembership.getUser(),
            managerMembership,
            invitation.getWorkspace(),
            "WORKSPACE_INVITATION",
            invitation.getId(),
            Map.of("email", invitation.getEmail()),
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );
    }

    private WorkspaceInvitation createPendingInvitation(
            GitRepository repository,
            Membership managerMembership,
            String normalizedEmail,
            AccountRequestContext context) {
        String rawToken = tokenGenerator.generate();
        WorkspaceInvitation invitation = new WorkspaceInvitation();
        invitation.setWorkspace(repository.getWorkspace());
        invitation.setEmail(normalizedEmail);
        invitation.setRole(MembershipRole.LEAD);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setInvitedBy(managerMembership);
        invitation.setExpiresAt(clock.instant().plus(INVITATION_TTL));
        invitation.setTokenHash(tokenHasher.hashInvitationToken(rawToken));
        invitation = invitationRepository.saveAndFlush(invitation);

        eventPublisher.publishEvent(new InvitationMailRequested(
            invitation.getId(),
            normalizedEmail,
            repository.getWorkspace().getName(),
            rawToken,
            context != null ? context.traceId() : null
        ));

        auditService.record(
            AuditAction.INVITATION_CREATED,
            managerMembership.getUser(),
            managerMembership,
            repository.getWorkspace(),
            "WORKSPACE_INVITATION",
            invitation.getId(),
            Map.of("email", normalizedEmail, "role", MembershipRole.LEAD.name()),
            context != null ? context.ipAddress() : null,
            context != null ? context.userAgent() : null
        );

        return invitation;
    }

    private RepositoryLeadAssignment createLeadInvitationAssignment(
            GitRepository repository,
            WorkspaceInvitation invitation,
            Membership managerMembership) {
        RepositoryLeadAssignment assignment = new RepositoryLeadAssignment();
        assignment.setWorkspace(repository.getWorkspace());
        assignment.setRepository(repository);
        assignment.setInvitation(invitation);
        assignment.setAssignedBy(managerMembership);
        assignment.setAssignedAt(clock.instant());
        return leadAssignmentRepository.saveAndFlush(assignment);
    }

    private void verifyCurrentWorkspaceManager(Membership membership, UUID workspaceId) {
        if (membership == null
                || membership.getRole() != MembershipRole.MANAGER
                || membership.getWorkspace() == null
                || !membership.getWorkspace().getId().equals(workspaceId)) {
            throw new ApiException(ProblemCode.MANAGER_REQUIRED);
        }
    }

    private static String normalizeEmail(CreateRepositoryLeadInvitationRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Email is required.");
        }
        return request.email().trim().toLowerCase(Locale.ROOT);
    }
}
