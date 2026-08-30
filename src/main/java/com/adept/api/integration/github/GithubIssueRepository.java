package com.adept.api.integration.github;

import java.util.Collection;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.adept.api.common.domain.IssueState;

public interface GithubIssueRepository extends JpaRepository<GithubIssue, UUID> {

    @Query(
        value = """
            select issue
            from GithubIssue issue
            join fetch issue.repository repository
            where issue.workspace.id = :workspaceId
              and repository.id in :repositoryIds
              and issue.state = :state
            order by coalesce(issue.githubUpdatedAt, issue.githubCreatedAt) desc,
                     issue.githubCreatedAt desc,
                     issue.id asc
            """,
        countQuery = """
            select count(issue)
            from GithubIssue issue
            where issue.workspace.id = :workspaceId
              and issue.repository.id in :repositoryIds
              and issue.state = :state
            """
    )
    Page<GithubIssue> findByProjectScope(
        @Param("workspaceId") UUID workspaceId,
        @Param("repositoryIds") Collection<UUID> repositoryIds,
        @Param("state") IssueState state,
        Pageable pageable
    );
}
