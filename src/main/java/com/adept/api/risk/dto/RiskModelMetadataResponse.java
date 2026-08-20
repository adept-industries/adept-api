package com.adept.api.risk.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RiskModelMetadataResponse(
    String modelName,
    String modelVersion,
    Instant trainedAt,
    String featureSchemaVersion,
    List<String> featureNames,
    Map<String, Object> thresholds,
    Map<String, Object> metrics,
    boolean isDemo
) {}
