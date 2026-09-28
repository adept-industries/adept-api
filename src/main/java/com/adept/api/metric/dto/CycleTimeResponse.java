package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.adept.api.common.domain.MetricGranularity;
import com.adept.api.metric.CycleTimeStage;

import io.swagger.v3.oas.annotations.media.Schema;

public record CycleTimeResponse(
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
    @Schema(description = "Merged pull requests in the range with at least one measurable stage.")
    int pullRequestCount,
    @Schema(description = "Merged pull requests in the range that no human reviewed before merge.")
    int unreviewedPullRequestCount,
    @Schema(description = "Stage with the longest median, or null when there is no data.")
    CycleTimeStage bottleneck,
    List<CycleTimeStageDto> stages,
    List<CycleTimePeriodDto> series
) {}
