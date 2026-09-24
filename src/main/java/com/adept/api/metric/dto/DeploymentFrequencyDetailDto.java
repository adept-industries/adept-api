package com.adept.api.metric.dto;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.DeploymentSource;

public record DeploymentFrequencyDetailDto(
    UUID id,
    UUID repositoryId,
    String repositoryName,
    String repositoryFullName,
    Instant deployedAt,
    String environment,
    DeploymentSource source,
    String commitSha,
    Long durationSeconds
) {
}
