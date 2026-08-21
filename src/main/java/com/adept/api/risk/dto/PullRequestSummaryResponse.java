package com.adept.api.risk.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PullRequestSummaryResponse(
    UUID id,
    UUID repositoryId,
    int number,
    String title,
    String state,
    boolean draft,
    String authorLogin,
    int additions,
    int deletions,
    int changedFiles,
    Instant openedAt,
    Instant mergedAt,
    Instant closedAt,
    BigDecimal riskScore,
    String riskLevel,
    List<Map<String, Object>> topFactors,
    String modelVersion,
    Instant predictedAt,
    boolean isStale
) {}
