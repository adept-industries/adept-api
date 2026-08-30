package com.adept.api.issue.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectJiraIssueResponse(
    UUID id,
    UUID jiraProjectId,
    String jiraProjectKey,
    String jiraProjectName,
    String issueKey,
    String summary,
    String issueType,
    String statusName,
    String priorityName,
    String url,
    Instant createdAt,
    Instant updatedAt
) {
}
