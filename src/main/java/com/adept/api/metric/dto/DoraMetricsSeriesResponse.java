package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.adept.api.common.domain.MetricGranularity;

public record DoraMetricsSeriesResponse(
    UUID workspaceId,
    UUID projectId,
    UUID repositoryId,
    int repositoryCount,
    Instant periodStart,
    Instant periodEnd,
    String timezone,
    MetricGranularity granularity,
    String calculationVersion,
    Instant calculatedAt,
    boolean stale,
    List<MetricSeriesItemDto> series
) {}
