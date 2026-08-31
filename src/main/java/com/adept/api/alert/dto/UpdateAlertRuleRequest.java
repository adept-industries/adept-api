package com.adept.api.alert.dto;

import java.math.BigDecimal;

import com.adept.api.common.domain.AlertComparator;
import com.adept.api.common.domain.AlertMetricType;
import com.adept.api.common.domain.NotificationChannel;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import io.swagger.v3.oas.annotations.media.Schema;

public class UpdateAlertRuleRequest {

    private String name;
    private boolean namePresent;

    private AlertMetricType metricType;
    private boolean metricTypePresent;

    private AlertComparator comparator;
    private boolean comparatorPresent;

    private BigDecimal thresholdValue;
    private boolean thresholdValuePresent;

    private Integer evaluationWindowMinutes;
    private boolean evaluationWindowMinutesPresent;

    private Integer cooldownMinutes;
    private boolean cooldownMinutesPresent;

    private NotificationChannel channel;
    private boolean channelPresent;

    private String destination;
    private boolean destinationPresent;

    private Boolean enabled;
    private boolean enabledPresent;

    public UpdateAlertRuleRequest() {
    }

    @JsonSetter(value = "name", nulls = Nulls.SET)
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    public String getName() {
        return name;
    }

    @Schema(hidden = true)
    public boolean isNamePresent() {
        return namePresent;
    }

    @JsonSetter(value = "metricType", nulls = Nulls.SET)
    public void setMetricType(AlertMetricType metricType) {
        this.metricType = metricType;
        this.metricTypePresent = true;
    }

    public AlertMetricType getMetricType() {
        return metricType;
    }

    @Schema(hidden = true)
    public boolean isMetricTypePresent() {
        return metricTypePresent;
    }

    @JsonSetter(value = "comparator", nulls = Nulls.SET)
    public void setComparator(AlertComparator comparator) {
        this.comparator = comparator;
        this.comparatorPresent = true;
    }

    public AlertComparator getComparator() {
        return comparator;
    }

    @Schema(hidden = true)
    public boolean isComparatorPresent() {
        return comparatorPresent;
    }

    @JsonSetter(value = "thresholdValue", nulls = Nulls.SET)
    public void setThresholdValue(BigDecimal thresholdValue) {
        this.thresholdValue = thresholdValue;
        this.thresholdValuePresent = true;
    }

    public BigDecimal getThresholdValue() {
        return thresholdValue;
    }

    @Schema(hidden = true)
    public boolean isThresholdValuePresent() {
        return thresholdValuePresent;
    }

    @JsonSetter(value = "evaluationWindowMinutes", nulls = Nulls.SET)
    public void setEvaluationWindowMinutes(Integer evaluationWindowMinutes) {
        this.evaluationWindowMinutes = evaluationWindowMinutes;
        this.evaluationWindowMinutesPresent = true;
    }

    public Integer getEvaluationWindowMinutes() {
        return evaluationWindowMinutes;
    }

    @Schema(hidden = true)
    public boolean isEvaluationWindowMinutesPresent() {
        return evaluationWindowMinutesPresent;
    }

    @JsonSetter(value = "cooldownMinutes", nulls = Nulls.SET)
    public void setCooldownMinutes(Integer cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
        this.cooldownMinutesPresent = true;
    }

    public Integer getCooldownMinutes() {
        return cooldownMinutes;
    }

    @Schema(hidden = true)
    public boolean isCooldownMinutesPresent() {
        return cooldownMinutesPresent;
    }

    @JsonSetter(value = "channel", nulls = Nulls.SET)
    public void setChannel(NotificationChannel channel) {
        this.channel = channel;
        this.channelPresent = true;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    @Schema(hidden = true)
    public boolean isChannelPresent() {
        return channelPresent;
    }

    @JsonSetter(value = "destination", nulls = Nulls.SET)
    public void setDestination(String destination) {
        this.destination = destination;
        this.destinationPresent = true;
    }

    public String getDestination() {
        return destination;
    }

    @Schema(hidden = true)
    public boolean isDestinationPresent() {
        return destinationPresent;
    }

    @JsonSetter(value = "enabled", nulls = Nulls.SET)
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
        this.enabledPresent = true;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    @Schema(hidden = true)
    public boolean isEnabledPresent() {
        return enabledPresent;
    }

    public void validate() {
        if (!namePresent
                && !metricTypePresent
                && !comparatorPresent
                && !thresholdValuePresent
                && !evaluationWindowMinutesPresent
                && !cooldownMinutesPresent
                && !channelPresent
                && !destinationPresent
                && !enabledPresent) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "At least one alert rule field must be provided.");
        }

        if (namePresent) {
            if (name == null || name.isBlank() || name.length() > 160) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Alert rule name must contain 1 to 160 characters.");
            }
        }

        if (metricTypePresent && metricType == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Metric type cannot be null.");
        }

        if (comparatorPresent && comparator == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Comparator cannot be null.");
        }

        if (thresholdValuePresent && thresholdValue == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Threshold value cannot be null.");
        }

        if (evaluationWindowMinutesPresent) {
            if (evaluationWindowMinutes == null || evaluationWindowMinutes < 1) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Evaluation window minutes must be greater than or equal to 1.");
            }
        }

        if (cooldownMinutesPresent) {
            if (cooldownMinutes == null || cooldownMinutes < 0) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Cooldown minutes must be greater than or equal to 0.");
            }
        }

        if (channelPresent && channel == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Channel cannot be null.");
        }

        if (destinationPresent) {
            if (destination == null || destination.isBlank() || destination.length() > 320 || !destination.contains("@")) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Destination must be a valid email address up to 320 characters.");
            }
        }

        if (enabledPresent && enabled == null) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Enabled flag cannot be null.");
        }
    }
}
