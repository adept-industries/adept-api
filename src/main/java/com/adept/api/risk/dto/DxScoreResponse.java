package com.adept.api.risk.dto;

import java.util.Map;
import java.util.UUID;

public record DxScoreResponse(
    UUID repositoryId,
    String repositoryName,
    double score,
    Map<String, Double> components,
    Map<String, Double> weights
) {}
