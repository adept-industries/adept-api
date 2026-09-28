package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record CycleTimePeriodDto(
    @Schema(description = "Start of the calendar day, Monday-start week or month in the workspace timezone. "
        + "The first period can start before the requested range; only merges inside the range are counted.")
    Instant periodStart,
    @Schema(description = "Exclusive end of the calendar period. The last period can end after the requested range.")
    Instant periodEnd,
    @Schema(description = "Merged pull requests in this period and inside the requested range.")
    int pullRequestCount,
    List<CycleTimeStageDto> stages
) {}
