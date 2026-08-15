package com.adept.api.integration.jira.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record MapRepositoryJiraProjectsRequest(
    @NotNull(message = "jiraProjectIds must not be null")
    List<UUID> jiraProjectIds
) {
}
