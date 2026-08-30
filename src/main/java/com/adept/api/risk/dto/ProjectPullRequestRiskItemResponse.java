package com.adept.api.risk.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.adept.api.common.domain.RiskLevel;

public record ProjectPullRequestRiskItemResponse(
    UUID pullRequestId,
    UUID repositoryId,
    String repositoryFullName,
    int number,
    String title,
    boolean draft,
    String authorLogin,
    String url,
    Instant openedAt,
    boolean stalled,
    BigDecimal riskScore,
    RiskLevel riskLevel,
    BigDecimal thresholdUsed,
    List<Map<String, Object>> topFactors,
    Instant predictedAt
) {
}
