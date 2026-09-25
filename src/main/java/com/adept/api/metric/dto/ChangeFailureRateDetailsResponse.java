package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Paginated response for the Change Failure Rate event details endpoint.
 *
 * <p>Pagination fields mirror {@link DeploymentFrequencyDetailsResponse}. The summary
 * fields cover the whole window, not only the current page: {@code totalDeployments} is
 * the engine's denominator, {@code failedDeployments} its numerator, and
 * {@code failureRatePercent} their ratio rounded to two decimals.
 */
public record ChangeFailureRateDetailsResponse(
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
    long    totalDeployments,
    long    failedDeployments,
    double  failureRatePercent,
    List<ChangeFailureRateDetailDto> items
) {}
