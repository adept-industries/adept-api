package com.adept.api.integration.jira;

import java.util.Collection;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JiraIssueRepository extends JpaRepository<JiraIssue, UUID> {

    @Query(
        value = """
            select issue
            from JiraIssue issue
            join fetch issue.jiraProject jiraProject
            join fetch jiraProject.jiraIntegration
            where issue.workspace.id = :workspaceId
              and jiraProject.id in :jiraProjectIds
              and issue.resolvedAt is null
            order by coalesce(issue.jiraUpdatedAt, issue.jiraCreatedAt) desc,
                     issue.jiraCreatedAt desc,
                     issue.id asc
            """,
        countQuery = """
            select count(issue)
            from JiraIssue issue
            where issue.workspace.id = :workspaceId
              and issue.jiraProject.id in :jiraProjectIds
              and issue.resolvedAt is null
            """
    )
    Page<JiraIssue> findOpenByProjectScope(
        @Param("workspaceId") UUID workspaceId,
        @Param("jiraProjectIds") Collection<UUID> jiraProjectIds,
        Pageable pageable
    );
}
