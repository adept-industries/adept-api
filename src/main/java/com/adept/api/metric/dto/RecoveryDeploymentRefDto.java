package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.UUID;

/** Compact reference to a deployment correlated with an incident. */
public record RecoveryDeploymentRefDto(
    UUID    id,
    String  commitSha,
    String  environment,
    Instant finishedAt
) {}
