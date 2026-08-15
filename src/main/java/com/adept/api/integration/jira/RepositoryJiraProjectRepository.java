package com.adept.api.integration.jira;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositoryJiraProjectRepository extends JpaRepository<RepositoryJiraProject, RepositoryJiraProjectId> {

    @Query("""
        select rjp
        from RepositoryJiraProject rjp
        join fetch rjp.jiraProject
        where rjp.repository.id = :repositoryId
        """)
    List<RepositoryJiraProject> findAllByRepositoryIdWithProject(@Param("repositoryId") UUID repositoryId);

    @Modifying
    @Query("delete from RepositoryJiraProject rjp where rjp.repository.id = :repositoryId")
    void deleteAllByRepositoryId(@Param("repositoryId") UUID repositoryId);

    @Query("""
        select rjp
        from RepositoryJiraProject rjp
        where rjp.repository.workspace.id = :workspaceId
        """)
    List<RepositoryJiraProject> findAllByWorkspaceId(@Param("workspaceId") UUID workspaceId);
}
