package com.adept.api.issue.dto;

import java.util.List;

public record ProjectGithubIssuePageResponse(
    List<ProjectGithubIssueResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
