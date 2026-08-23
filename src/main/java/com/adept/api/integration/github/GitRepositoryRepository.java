package com.adept.api.integration.github;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    Optional<GitRepository> findByWorkspaceIdAndGithubRepoId(
        UUID workspaceId,
        long githubRepoId
    );

    Optional<GitRepository> findByGithubIntegrationIdAndGithubRepoId(
        UUID githubIntegrationId,
        long githubRepoId
    );

    Optional<GitRepository> findFirstByGithubRepoId(long githubRepoId);

    Optional<GitRepository> findFirstByFullName(String fullName);

    List<GitRepository> findAllByWorkspaceId(UUID workspaceId);

    List<GitRepository> findAllByGithubIntegrationId(UUID githubIntegrationId);

    int countByGithubIntegrationId(UUID githubIntegrationId);

    List<GitRepository> findAllByIdInAndWorkspaceId(
        Collection<UUID> ids,
        UUID workspaceId
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

    @Query("""
        select r
        from GitRepository r
        join RepositoryLeadAssignment a on a.repository = r
        where r.workspace.id = :workspaceId
          and a.leadMembership.id = :membershipId
          and r.trackingEnabled = true
          and r.archived = false
        order by lower(r.fullName), r.id
        """)
    List<GitRepository> findAllLeadReadableRepositories(
        @Param("workspaceId") UUID workspaceId,
        @Param("membershipId") UUID membershipId
    );
}
