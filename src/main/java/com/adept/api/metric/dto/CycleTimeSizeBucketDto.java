package com.adept.api.metric.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

public record CycleTimeSizeBucketDto(
    @Schema(description = "Changed-line bucket: S <= 100, M <= 400, L <= 1000, XL > 1000.")
    String size,
    int pullRequestCount,
    @Schema(description = "Pull requests in the bucket that received at least one human review.")
    int reviewedPullRequestCount,
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(
        description = "Median hours from ready for review to merge, or null when no pull request in the bucket has one.",
        nullable = true
    )
    BigDecimal mergeMedianHours
) {}
