package com.adept.api.metric.dto;

import java.math.BigDecimal;

import com.adept.api.metric.CycleTimeStage;

import io.swagger.v3.oas.annotations.media.Schema;

public record CycleTimeStageDto(
    CycleTimeStage stage,
    @Schema(description = "Median hours spent in this stage; zero when sampleSize is zero.")
    BigDecimal medianHours,
    BigDecimal p75Hours,
    @Schema(description = "Merged pull requests with a measurable duration for this stage.")
    int sampleSize
) {}
