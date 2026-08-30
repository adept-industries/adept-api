package com.adept.api.risk.dto;

import java.time.Instant;
import java.util.List;

public record ProjectPullRequestRiskPageResponse(
    String displayLabel,
    String disclaimer,
    String modelName,
    String modelVersion,
    String featureSchemaVersion,
    Instant stalledBefore,
    List<ProjectPullRequestRiskItemResponse> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
