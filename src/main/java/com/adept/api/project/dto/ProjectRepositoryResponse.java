package com.adept.api.project.dto;

import java.util.List;
import java.util.UUID;

import com.adept.api.integration.github.GitRepository;

public record ProjectRepositoryResponse(
    UUID id,
    String fullName,
    boolean trackingEnabled,
    boolean archived,
    List<ProjectJiraProjectResponse> jiraProjects
) {
    public static ProjectRepositoryResponse from(
            GitRepository repository,
            List<ProjectJiraProjectResponse> jiraProjects) {
        return new ProjectRepositoryResponse(
            repository.getId(),
            repository.getFullName(),
            repository.isTrackingEnabled(),
            repository.isArchived(),
            List.copyOf(jiraProjects)
        );
    }
}
