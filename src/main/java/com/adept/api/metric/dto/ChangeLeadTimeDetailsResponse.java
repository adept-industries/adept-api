package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Paginated response for the Change Lead Time event details endpoint.
 *
 * <p>Shape deliberately mirrors {@link DeploymentFrequencyDetailsResponse} so the
 * front-end pagination component can be reused without modification.
 */
public record ChangeLeadTimeDetailsResponse(
    UUID   workspaceId,
    UUID   projectId,
    UUID   repositoryId,
    int    repositoryCount,
    Instant rangeStart,
    Instant rangeEnd,
    String timezone,
    int    page,
    int    size,
    long   totalElements,
    int    totalPages,
    List<ChangeLeadTimeDetailDto> items
) {}
