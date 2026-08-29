package com.adept.api.risk.dto;

public record PrRiskBroadcastEvent(
    String prTitle,
    int riskScore,
    String riskLevel,
    Double probability
) {}
