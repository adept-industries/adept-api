package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Paginated response for the Recovery Time event details endpoint.
 *
 * <p>Shape mirrors {@link DeploymentFrequencyDetailsResponse} so the front-end
 * pagination component can be reused without modification.
 */
public record RecoveryTimeDetailsResponse(
    UUID    workspaceId,
    UUID    projectId,
    UUID    repositoryId,
    int     repositoryCount,
    Instant rangeStart,
    Instant rangeEnd,
    String  timezone,
    int     page,
    int     size,
    long    totalElements,
    int     totalPages,
    List<RecoveryTimeDetailDto> items
) {}
