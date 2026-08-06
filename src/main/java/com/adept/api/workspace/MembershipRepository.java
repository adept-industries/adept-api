package com.adept.api.workspace;
import com.adept.api.common.domain.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository
    extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByIdAndStatus(
        UUID id,
        MembershipStatus status
    );

    Optional<Membership> findByWorkspaceIdAndUserId(
        UUID workspaceId,
        UUID userId
    );

    List<Membership> findAllByUserIdAndStatus(
        UUID userId,
        MembershipStatus status
    );

    @Query("""
        select m
        from Membership m
        join fetch m.workspace
        where m.user.id = :userId
          and m.status = :status
        order by m.workspace.name asc, m.id asc
        """)
    List<Membership> findAllActiveByUserIdWithWorkspace(
        @Param("userId") UUID userId,
        @Param("status") MembershipStatus status
    );
}
