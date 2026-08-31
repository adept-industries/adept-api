package com.adept.api.alert.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.adept.api.common.domain.AlertComparator;
import com.adept.api.common.domain.AlertMetricType;
import com.adept.api.common.domain.NotificationChannel;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAlertRuleRequest(
    @NotNull
    UUID repositoryId,

    @NotBlank
    @Size(max = 160)
    String name,

    @NotNull
    AlertMetricType metricType,

    @NotNull
    AlertComparator comparator,

    @NotNull
    BigDecimal thresholdValue,

    @Min(1)
    Integer evaluationWindowMinutes,

    @Min(0)
    Integer cooldownMinutes,

    NotificationChannel channel,

    @Email
    @Size(max = 320)
    String destination,

    Boolean enabled
) {
}
