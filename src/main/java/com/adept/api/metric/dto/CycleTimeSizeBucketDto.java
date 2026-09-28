package com.adept.api.metric.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

public record CycleTimeSizeBucketDto(
    @Schema(description = "Changed-line bucket: XS <= 10, S <= 100, M <= 400, L <= 1000, XL > 1000.")
    String size,
    int pullRequestCount,
    @Schema(description = "Median pickup hours, or null when no pull request in the bucket was reviewed.")
    BigDecimal pickupMedianHours,
    @Schema(description = "Median review hours, or null when no pull request in the bucket was approved.")
    BigDecimal reviewMedianHours
) {}
