package com.adept.api.workspace.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteWorkspaceRequest(
    @NotBlank String confirmationSlug
) {}
