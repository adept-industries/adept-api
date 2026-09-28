package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.List;

public record CycleTimePeriodDto(
    Instant periodStart,
    Instant periodEnd,
    int pullRequestCount,
    List<CycleTimeStageDto> stages
) {}
