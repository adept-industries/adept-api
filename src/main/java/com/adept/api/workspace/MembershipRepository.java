package com.adept.api.workspace;
import com.adept.api.common.domain.MembershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
