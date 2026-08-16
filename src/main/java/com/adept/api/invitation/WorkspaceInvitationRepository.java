package com.adept.api.invitation;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select i
        from WorkspaceInvitation i
        where i.workspace.id = :workspaceId
          and lower(i.email) = lower(:email)
          and i.status = com.adept.api.common.domain.InvitationStatus.PENDING
        """)
    Optional<WorkspaceInvitation> findPendingByWorkspaceIdAndEmailForUpdate(
        @Param("workspaceId") UUID workspaceId,
        @Param("email") String email
    );
}
