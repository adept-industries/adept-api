package com.adept.api.invitation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.adept.api.common.domain.InvitationStatus;

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

    Optional<WorkspaceInvitation> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select i
        from WorkspaceInvitation i
        where i.tokenHash = :tokenHash
        """)
    Optional<WorkspaceInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<WorkspaceInvitation> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select i
        from WorkspaceInvitation i
        where i.id = :id and i.workspace.id = :workspaceId
        """)
    Optional<WorkspaceInvitation> findByIdAndWorkspaceIdForUpdate(
        @Param("id") UUID id,
        @Param("workspaceId") UUID workspaceId
    );

    List<WorkspaceInvitation> findAllByWorkspaceIdAndStatus(UUID workspaceId, InvitationStatus status);
}
