package com.adept.api.metric.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.adept.api.metric.MetricRating;

public record MetricSummaryDto(
    BigDecimal value,
    String unit,
    int sampleSize,
    MetricRating rating,
    Map<String, Object> dimensions
) {
    public static MetricSummaryDto empty(String unit) {
        return new MetricSummaryDto(BigDecimal.ZERO, unit, 0, MetricRating.UNKNOWN, Map.of());
    }
}
