package com.adept.api.metric.dto;

import java.util.List;
import java.util.UUID;

import com.adept.api.common.domain.MetricGranularity;

public record DoraMetricsSeriesResponse(
    UUID workspaceId,
    UUID projectId,
    UUID repositoryId,
    int repositoryCount,
    MetricGranularity granularity,
    List<MetricSeriesItemDto> series
) {}
