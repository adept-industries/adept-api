package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.UUID;

public record DoraMetricsSummaryResponse(
    UUID workspaceId,
    UUID projectId,
    UUID repositoryId,
    int repositoryCount,
    Instant periodStart,
    Instant periodEnd,
    MetricSummaryDto deploymentFrequency,
    MetricSummaryDto changeLeadTime,
    MetricSummaryDto recoveryTime,
    MetricSummaryDto changeFailureRate,
    Instant calculatedAt
) {}
