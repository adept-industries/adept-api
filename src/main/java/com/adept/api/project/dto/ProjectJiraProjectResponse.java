package com.adept.api.project.dto;

import java.util.UUID;

import com.adept.api.integration.jira.JiraProject;

public record ProjectJiraProjectResponse(
    UUID id,
    String projectKey,
    String projectName,
    boolean trackingEnabled
) {
    public static ProjectJiraProjectResponse from(JiraProject project) {
        return new ProjectJiraProjectResponse(
            project.getId(),
            project.getProjectKey(),
            project.getProjectName(),
            project.isTrackingEnabled()
        );
    }
}
