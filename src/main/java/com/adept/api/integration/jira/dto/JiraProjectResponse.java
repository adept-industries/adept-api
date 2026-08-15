package com.adept.api.integration.jira.dto;

import java.time.Instant;
import java.util.UUID;

public record JiraProjectResponse(
    UUID id,
    UUID workspaceId,
    UUID jiraIntegrationId,
    String jiraProjectId,
    String projectKey,
    String projectName,
    String projectType,
    boolean trackingEnabled,
    Instant lastSyncedAt
) {
}
