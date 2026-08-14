package com.adept.api.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.domain.UserStatus;
import com.adept.api.common.domain.WorkspaceStatus;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.MembershipRepository;

@Service
@Transactional(readOnly = true)
public class PrincipalValidationService {

    public record ValidatedPrincipal(AuthenticatedPrincipal principal, Membership membership) {
    }

    private final MembershipRepository membershipRepository;

    public PrincipalValidationService(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public Optional<ValidatedPrincipal> validate(
            UUID userId,
            UUID membershipId,
            UUID workspaceId,
            MembershipRole claimedRole,
            int claimedTokenVersion) {
        return validate(userId, membershipId, workspaceId, claimedRole, claimedTokenVersion, null);
    }

    public Optional<ValidatedPrincipal> validate(
            UUID userId,
            UUID membershipId,
            UUID workspaceId,
            MembershipRole claimedRole,
            int claimedTokenVersion,
            Instant authenticatedAt) {
        if (userId == null || membershipId == null || workspaceId == null || claimedRole == null) {
            return Optional.empty();
        }

        Optional<Membership> membershipOpt = membershipRepository.findByIdWithUserAndWorkspace(membershipId);
        if (membershipOpt.isEmpty()) {
            return Optional.empty();
        }

        Membership membership = membershipOpt.get();

        if (!membership.getUser().getId().equals(userId)
                || !membership.getWorkspace().getId().equals(workspaceId)) {
            return Optional.empty();
        }

        if (membership.getUser().getStatus() != UserStatus.ACTIVE
                || membership.getUser().getEmailVerifiedAt() == null) {
            return Optional.empty();
        }

        if (membership.getUser().getTokenVersion() != claimedTokenVersion) {
            return Optional.empty();
        }

        if (membership.getStatus() != MembershipStatus.ACTIVE
                || membership.getWorkspace().getStatus() != WorkspaceStatus.ACTIVE) {
            return Optional.empty();
        }

        if (membership.getRole() != MembershipRole.MANAGER
                && membership.getRole() != MembershipRole.LEAD) {
            return Optional.empty();
        }

        if (membership.getRole() != claimedRole) {
            return Optional.empty();
        }

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            membership.getUser().getId(),
            membership.getId(),
            membership.getWorkspace().getId(),
            membership.getRole(),
            membership.getUser().getTokenVersion(),
            authenticatedAt
        );

        return Optional.of(new ValidatedPrincipal(principal, membership));
    }
}
