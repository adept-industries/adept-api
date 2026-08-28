package com.adept.api.project.dto;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProjectRepositoryConfigurationRequest(
    @NotNull UUID repositoryId,
    @NotNull @Size(max = 100) Set<@NotNull UUID> jiraProjectIds
) {
}
