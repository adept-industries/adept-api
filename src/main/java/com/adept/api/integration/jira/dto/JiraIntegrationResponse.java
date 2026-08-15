package com.adept.api.integration.jira.dto;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.IntegrationStatus;

public record JiraIntegrationResponse(
    UUID id,
    UUID workspaceId,
    String cloudId,
    String siteUrl,
    String displayName,
    IntegrationStatus status,
    Instant lastSyncedAt,
    int projectCount
) {
}
