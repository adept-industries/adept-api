package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.IncidentSeverity;
import com.adept.api.common.domain.IncidentSource;

/**
 * One resolved incident returned by the Recovery Time details endpoint.
 *
 * <p>{@code resolvedAt} is the instant used by the metric: the recovery deployment's
 * finish time when one is linked, otherwise the incident's recorded resolution time.
 * {@code recoveryDurationSeconds} is {@code resolvedAt - detectedAt}.
 */
public record RecoveryTimeDetailDto(
    UUID             incidentId,
    String           title,
    IncidentSource   source,
    IncidentSeverity severity,

    UUID   repositoryId,
    String repositoryName,
    String repositoryFullName,

    Instant detectedAt,
    Instant resolvedAt,
    Long    recoveryDurationSeconds,

    /** Failing production deployment that opened the incident, when correlated. */
    RecoveryDeploymentRefDto failedDeployment,
    /** Deployment that restored service, when correlated. */
    RecoveryDeploymentRefDto recoveryDeployment
) {}
