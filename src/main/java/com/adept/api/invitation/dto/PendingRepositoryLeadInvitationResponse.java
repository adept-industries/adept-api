package com.adept.api.invitation.dto;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.InvitationStatus;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.integration.github.RepositoryLeadAssignment;
import com.adept.api.invitation.WorkspaceInvitation;
import com.adept.api.workspace.Membership;

public record PendingRepositoryLeadInvitationResponse(
    UUID assignmentId,
    UUID repositoryId,
    UUID invitationId,
    String email,
    MembershipRole role,
    InvitationStatus status,
    Instant expiresAt
) {
    public static PendingRepositoryLeadInvitationResponse from(
            RepositoryLeadAssignment assignment,
            WorkspaceInvitation invitation) {
        return new PendingRepositoryLeadInvitationResponse(
            assignment.getId(),
            assignment.getRepository().getId(),
            invitation.getId(),
            invitation.getEmail(),
            invitation.getRole(),
            invitation.getStatus(),
            invitation.getExpiresAt()
        );
    }

    public static PendingRepositoryLeadInvitationResponse from(
            RepositoryLeadAssignment assignment,
            Membership leadMembership) {
        return new PendingRepositoryLeadInvitationResponse(
            assignment.getId(),
            assignment.getRepository().getId(),
            null,
            leadMembership.getUser().getEmail(),
            leadMembership.getRole(),
            null,
            null
        );
    }

    public static PendingRepositoryLeadInvitationResponse from(RepositoryLeadAssignment assignment) {
        if (assignment.getLeadMembership() != null) {
            return from(assignment, assignment.getLeadMembership());
        }
        if (assignment.getInvitation() != null) {
            return from(assignment, assignment.getInvitation());
        }
        return new PendingRepositoryLeadInvitationResponse(
            assignment.getId(),
            assignment.getRepository().getId(),
            null,
            null,
            MembershipRole.LEAD,
            null,
            null
        );
    }
}
