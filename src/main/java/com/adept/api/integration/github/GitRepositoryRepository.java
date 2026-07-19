package com.adept.api.integration.github;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GitRepositoryRepository
    extends JpaRepository<GitRepository, UUID> {

    Optional<GitRepository> findByIdAndWorkspaceId(
        UUID id,
        UUID workspaceId
    );

    Page<GitRepository> findAllByWorkspaceIdAndTrackingEnabledTrue(
        UUID workspaceId,
        Pageable pageable
    );

    @Query("""
        select r
        from GitRepository r
        join RepositoryLeadAssignment a on a.repository = r
        where r.workspace.id = :workspaceId
          and a.leadMembership.id = :membershipId
          and r.trackingEnabled = true
          and r.archived = false
        """)
    Page<GitRepository> findLeadReadableRepositories(
        @Param("workspaceId") UUID workspaceId,
        @Param("membershipId") UUID membershipId,
        Pageable pageable
    );
}
