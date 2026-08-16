package com.adept.api.invitation;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.InvitationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.integration.github.RepositoryLeadAssignment;
import com.adept.api.integration.github.RepositoryLeadAssignmentRepository;
import com.adept.api.invitation.dto.CreateRepositoryLeadInvitationRequest;
import com.adept.api.invitation.dto.PendingRepositoryLeadInvitationResponse;
import com.adept.api.user.UserRepository;
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
    private final SecureTokenGenerator tokenGenerator;
    private final TokenHasher tokenHasher;
    private final Clock clock;

    public InvitationService(
            WorkspaceInvitationRepository invitationRepository,
            RepositoryLeadAssignmentRepository leadAssignmentRepository,
            GitRepositoryRepository gitRepositoryRepository,
            UserRepository userRepository,
            MembershipRepository membershipRepository,
            SecureTokenGenerator tokenGenerator,
            TokenHasher tokenHasher,
            Clock clock) {
        this.invitationRepository = invitationRepository;
        this.leadAssignmentRepository = leadAssignmentRepository;
        this.gitRepositoryRepository = gitRepositoryRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
    }

    @Transactional
    public PendingRepositoryLeadInvitationResponse createPendingRepositoryLeadInvitation(
            UUID workspaceId,
            UUID repositoryId,
            Membership managerMembership,
            CreateRepositoryLeadInvitationRequest request) {
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
            .orElseGet(() -> createPendingInvitation(repository, managerMembership, normalizedEmail));

        RepositoryLeadAssignment assignment = leadAssignmentRepository
            .findByRepositoryIdAndInvitationId(repositoryId, invitation.getId())
            .orElseGet(() -> createLeadInvitationAssignment(repository, invitation, managerMembership));

        return PendingRepositoryLeadInvitationResponse.from(assignment, invitation);
    }

    private WorkspaceInvitation createPendingInvitation(
            GitRepository repository,
            Membership managerMembership,
            String normalizedEmail) {
        WorkspaceInvitation invitation = new WorkspaceInvitation();
        invitation.setWorkspace(repository.getWorkspace());
        invitation.setEmail(normalizedEmail);
        invitation.setRole(MembershipRole.LEAD);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setInvitedBy(managerMembership);
        invitation.setExpiresAt(clock.instant().plus(INVITATION_TTL));
        invitation.setTokenHash(tokenHasher.hashInvitationToken(tokenGenerator.generate()));
        return invitationRepository.saveAndFlush(invitation);
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
