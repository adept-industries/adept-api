package com.adept.api.integration.github;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.invitation.WorkspaceInvitation;
import com.adept.api.workspace.*;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "repository_lead_assignments",
       uniqueConstraints = {
           @UniqueConstraint(
               name = "uq_repository_lead_assignment_membership",
               columnNames = {"repository_id", "lead_membership_id"}
           ),
           @UniqueConstraint(
               name = "uq_repository_lead_assignment_invitation",
               columnNames = {"repository_id", "invitation_id"}
           )
       })
// One of potentially several Lead assignments for a repository.
public class RepositoryLeadAssignment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private GitRepository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lead_membership_id")
    private Membership leadMembership;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitation_id")
    private WorkspaceInvitation invitation;

    // Nullable provenance after the assigning Manager leaves the workspace.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_membership_id")
    private Membership assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();
}
