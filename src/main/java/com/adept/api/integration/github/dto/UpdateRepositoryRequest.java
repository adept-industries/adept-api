package com.adept.api.integration.github.dto;

import jakarta.validation.Valid;

public record UpdateRepositoryRequest(
    Boolean trackingEnabled,
    @Valid RepositorySettingsDto settings
) {
}
