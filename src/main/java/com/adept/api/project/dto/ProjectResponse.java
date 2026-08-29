package com.adept.api.project.dto;

import java.util.List;
import java.util.UUID;

import com.adept.api.project.Project;

public record ProjectResponse(
    UUID id,
    UUID workspaceId,
    String name,
    String description,
    List<ProjectRepositoryResponse> repositories,
    List<ProjectJiraProjectResponse> jiraProjects
) {
    public static ProjectResponse from(
            Project project,
            List<ProjectRepositoryResponse> repositories,
            List<ProjectJiraProjectResponse> jiraProjects) {
        return new ProjectResponse(
            project.getId(),
            project.getWorkspace().getId(),
            project.getName(),
            project.getDescription(),
            List.copyOf(repositories),
            List.copyOf(jiraProjects)
        );
    }
}
