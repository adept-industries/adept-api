package com.adept.api.issue.dto;

public record ProjectIssueSyncResponse(
    int queuedGithubRepositories,
    int alreadyQueuedGithubRepositories,
    int queuedJiraIntegrations,
    int alreadyQueuedJiraIntegrations
) {
}
