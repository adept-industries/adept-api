package com.adept.api.metric.dto;

import java.util.UUID;

import com.adept.api.common.domain.IncidentSeverity;

/** Compact reference to an incident that names a deployment as its failed deployment. */
public record ChangeFailureRateIncidentRefDto(
    UUID             id,
    String           title,
    IncidentSeverity severity
) {}
