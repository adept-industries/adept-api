package com.adept.api.workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.adept.api.common.domain.MembershipStatus;
import jakarta.persistence.LockModeType;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Membership m WHERE m.id = :id")
    Optional<Membership> findByIdForUpdate(@Param("id") UUID id);

    Optional<Membership> findByIdAndStatus(UUID id, MembershipStatus status);

    Optional<Membership> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    List<Membership> findAllByUserId(UUID userId);

    List<Membership> findAllByUserIdAndStatus(UUID userId, MembershipStatus status);

    @Query("""
        SELECT m FROM Membership m
        JOIN FETCH m.workspace w
        WHERE m.user.id = :userId
          AND m.status = com.adept.api.common.domain.MembershipStatus.ACTIVE
          AND w.status = com.adept.api.common.domain.WorkspaceStatus.ACTIVE
        ORDER BY lower(w.name) ASC, w.id ASC
        """)
    List<Membership> findAllActiveWithWorkspaceByUserId(@Param("userId") UUID userId);

    @Query("""
        SELECT m FROM Membership m
        JOIN FETCH m.workspace w
        WHERE m.user.id = :userId
          AND w.id = :workspaceId
          AND m.status = com.adept.api.common.domain.MembershipStatus.ACTIVE
          AND w.status = com.adept.api.common.domain.WorkspaceStatus.ACTIVE
        """)
    Optional<Membership> findActiveByUserIdAndWorkspaceId(
        @Param("userId") UUID userId,
        @Param("workspaceId") UUID workspaceId
    );

    @Query("""
        SELECT m FROM Membership m
        JOIN FETCH m.user u
        JOIN FETCH m.workspace w
        WHERE m.id = :membershipId
        """)
    Optional<Membership> findByIdWithUserAndWorkspace(@Param("membershipId") UUID membershipId);
}
