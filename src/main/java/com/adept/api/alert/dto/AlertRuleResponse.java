package com.adept.api.alert.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.adept.api.alert.AlertRule;
import com.adept.api.common.domain.AlertComparator;
import com.adept.api.common.domain.AlertMetricType;
import com.adept.api.common.domain.NotificationChannel;

public record AlertRuleResponse(
    UUID id,
    UUID workspaceId,
    UUID repositoryId,
    String repositoryFullName,
    UUID createdByMembershipId,
    String name,
    AlertMetricType metricType,
    AlertComparator comparator,
    BigDecimal thresholdValue,
    int evaluationWindowMinutes,
    int cooldownMinutes,
    NotificationChannel channel,
    String destination,
    boolean enabled,
    Instant lastTriggeredAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static AlertRuleResponse from(AlertRule rule) {
        return new AlertRuleResponse(
            rule.getId(),
            rule.getWorkspace().getId(),
            rule.getRepository().getId(),
            rule.getRepository().getFullName(),
            rule.getCreatedBy() != null ? rule.getCreatedBy().getId() : null,
            rule.getName(),
            rule.getMetricType(),
            rule.getComparator(),
            rule.getThresholdValue(),
            rule.getEvaluationWindowMinutes(),
            rule.getCooldownMinutes(),
            rule.getChannel(),
            rule.getDestination(),
            rule.isEnabled(),
            rule.getLastTriggeredAt(),
            rule.getCreatedAt(),
            rule.getUpdatedAt()
        );
    }
}
