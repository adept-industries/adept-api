package com.adept.api.risk.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RiskyPullRequestResponse(
    UUID repositoryId,
    int prNumber,
    String title,
    String authorLogin,
    BigDecimal riskScore,
    String riskLevel,
    List<Map<String, Object>> topFactors,
    Instant predictedAt,
    String modelVersion
) {}
