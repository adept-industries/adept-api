package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.DeploymentStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One finished production deployment returned by the Change Failure Rate details endpoint.
 *
 * <p>{@code isFailure} mirrors the engine: the deployment counts toward the numerator
 * when its status is {@code FAILURE} or an incident is linked to it as the failed deployment.
 */
public record ChangeFailureRateDetailDto(
    UUID deploymentId,

    UUID   repositoryId,
    String repositoryName,
    String repositoryFullName,

    Instant          finishedAt,
    String           environment,
    DeploymentStatus status,
    String           commitSha,

    @JsonProperty("isFailure")
    boolean isFailure,

    /** Incident linked to this deployment as its failed deployment, when one exists. */
    ChangeFailureRateIncidentRefDto incident
) {}
