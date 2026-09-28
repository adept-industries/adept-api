package com.adept.api.metric.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

public record CycleTimeReviewRoundsDto(
    @Schema(description = "Merged pull requests that received at least one human review.")
    int reviewedPullRequestCount,
    @Schema(description = "Average number of changes-requested reviews per reviewed pull request.")
    BigDecimal averageRounds,
    int pullRequestsWithChangesRequested
) {}
