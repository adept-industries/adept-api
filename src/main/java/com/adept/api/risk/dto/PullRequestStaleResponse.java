package com.adept.api.risk.dto;

import java.util.UUID;

public record PullRequestStaleResponse(
    UUID repositoryId,
    int prNumber,
    boolean isStale,
    boolean isOpen,
    double thresholdHours,
    double hoursSinceLastActivity,
    String reason
) {}
