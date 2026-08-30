package com.adept.api.issue.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectGithubIssueResponse(
    UUID id,
    UUID repositoryId,
    String repositoryFullName,
    int number,
    String title,
    String authorLogin,
    List<String> assigneeLogins,
    List<String> labels,
    int commentsCount,
    String url,
    Instant createdAt,
    Instant updatedAt
) {
}
