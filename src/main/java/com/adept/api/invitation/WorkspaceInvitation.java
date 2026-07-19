package com.adept.api.invitation;

import com.adept.api.common.domain.*;
import com.adept.api.workspace.*;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "workspace_invitations")
// One row tracks a Lead invitation from creation through acceptance/revocation.
public class WorkspaceInvitation extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipRole role = MembershipRole.LEAD;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvitationStatus status = InvitationStatus.PENDING;

    // Nullable only after the inviter's membership is deleted; set at creation.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_membership_id")
    private Membership invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
