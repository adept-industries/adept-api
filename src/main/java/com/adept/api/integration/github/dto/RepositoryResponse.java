package com.adept.api.integration.github.dto;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.RepositoryVisibility;

public record RepositoryResponse(
    UUID id,
    UUID workspaceId,
    UUID githubIntegrationId,
    long githubRepoId,
    String ownerLogin,
    String name,
    String fullName,
    String defaultBranch,
    RepositoryVisibility visibility,
    boolean archived,
    boolean trackingEnabled,
    RepositorySettingsDto settings,
    Instant lastSyncedAt
) {
}
