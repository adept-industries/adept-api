package com.adept.api.metric.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import com.adept.api.common.domain.MetricType;

public record MetricSeriesItemDto(
    MetricType metricType,
    Instant periodStart,
    Instant periodEnd,
    BigDecimal value,
    String unit,
    int sampleSize,
    Map<String, Object> dimensions
) {}
