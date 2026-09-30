package com.adept.api.project;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectJiraProjectRepository extends JpaRepository<ProjectJiraProject, ProjectJiraProjectId> {
    @Query("""
        select mapping from ProjectJiraProject mapping
        join fetch mapping.jiraProject jiraProject
        where mapping.project.id = :projectId and mapping.workspaceId = :workspaceId
          and jiraProject.trackingEnabled = true
        order by lower(jiraProject.projectKey), jiraProject.id
        """)
    List<ProjectJiraProject> findAllTrackedByProjectIdAndWorkspaceIdWithProject(
        @Param("projectId") UUID projectId,
        @Param("workspaceId") UUID workspaceId
    );

    @Query("""
        select distinct mapping.project.name
        from ProjectJiraProject mapping
        where mapping.jiraProject.id = :jiraProjectId
          and mapping.workspaceId = :workspaceId
        order by mapping.project.name
        """)
    List<String> findProjectNamesByJiraProjectIdAndWorkspaceId(
        @Param("jiraProjectId") UUID jiraProjectId,
        @Param("workspaceId") UUID workspaceId
    );

    @Query("""
        select distinct mapping.project.name
        from ProjectJiraProject mapping
        where mapping.jiraProject.jiraIntegration.id = :integrationId
          and mapping.workspaceId = :workspaceId
        order by mapping.project.name
        """)
    List<String> findProjectNamesByJiraIntegrationIdAndWorkspaceId(
        @Param("integrationId") UUID integrationId,
        @Param("workspaceId") UUID workspaceId
    );

    @Modifying
    @Query("delete from ProjectJiraProject mapping where mapping.project.id = :projectId")
    void deleteAllByProjectId(@Param("projectId") UUID projectId);

    @Modifying
    @Query("delete from ProjectJiraProject mapping where mapping.jiraProject.id = :jiraProjectId and mapping.workspaceId = :workspaceId")
    void deleteAllByJiraProjectIdAndWorkspaceId(
        @Param("jiraProjectId") UUID jiraProjectId,
        @Param("workspaceId") UUID workspaceId
    );

    @Modifying
    @Query("delete from ProjectJiraProject mapping where mapping.jiraProject.jiraIntegration.id = :integrationId and mapping.workspaceId = :workspaceId")
    void deleteAllByJiraIntegrationIdAndWorkspaceId(
        @Param("integrationId") UUID integrationId,
        @Param("workspaceId") UUID workspaceId
    );
}
