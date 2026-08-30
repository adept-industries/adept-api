package com.adept.api.risk.dto;

public record ProjectPullRequestRiskRebuildResponse(
    String modelVersion,
    int queuedRepositories,
    int alreadyQueuedRepositories
) {
}
