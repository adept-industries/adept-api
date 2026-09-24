package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DeploymentFrequencyDetailsResponse(
    UUID workspaceId,
    UUID projectId,
    UUID repositoryId,
    int repositoryCount,
    Instant rangeStart,
    Instant rangeEnd,
    String timezone,
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<DeploymentFrequencyDetailDto> items
) {
}
