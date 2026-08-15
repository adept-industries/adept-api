package com.adept.api.integration.github.dto;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.GithubAccountType;
import com.adept.api.common.domain.IntegrationStatus;
import com.adept.api.common.domain.RepositorySelection;

public record GithubIntegrationResponse(
    UUID id,
    UUID workspaceId,
    long installationId,
    String accountLogin,
    GithubAccountType accountType,
    RepositorySelection repositorySelection,
    IntegrationStatus status,
    Instant installedAt,
    Instant lastSyncedAt,
    int repositoryCount
) {
}
