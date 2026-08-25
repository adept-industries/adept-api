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
    String timezone,
    String calculationVersion,
    MetricSummaryDto deploymentFrequency,
    MetricSummaryDto changeLeadTime,
    MetricSummaryDto recoveryTime,
    MetricSummaryDto changeFailureRate,
    Instant calculatedAt,
    boolean stale
) {}
