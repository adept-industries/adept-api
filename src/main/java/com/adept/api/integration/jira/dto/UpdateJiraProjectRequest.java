package com.adept.api.integration.jira.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateJiraProjectRequest(
    @NotNull(message = "trackingEnabled is required")
    Boolean trackingEnabled
) {
}
