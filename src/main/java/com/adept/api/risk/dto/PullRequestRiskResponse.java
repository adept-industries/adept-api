package com.adept.api.risk.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PullRequestRiskResponse(
    UUID repositoryId,
    int prNumber,
    BigDecimal riskProbability,
    String riskLevel,
    String modelVersion,
    BigDecimal thresholdUsed,
    List<Map<String, Object>> topFactors,
    Instant predictedAt,
    String stage
) {}
