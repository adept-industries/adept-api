package com.adept.api.workspace;

import com.adept.api.common.domain.*;
import com.adept.api.user.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "memberships",
       uniqueConstraints = @UniqueConstraint(name = "uq_membership_workspace_user",
                                             columnNames = {"workspace_id", "user_id"}))
// One row grants one user one role inside one workspace.
public class Membership extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipStatus status = MembershipStatus.ACTIVE;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;
}
