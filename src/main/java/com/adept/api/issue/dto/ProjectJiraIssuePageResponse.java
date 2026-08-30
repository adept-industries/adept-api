package com.adept.api.issue.dto;

import java.util.List;

public record ProjectJiraIssuePageResponse(
    List<ProjectJiraIssueResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
