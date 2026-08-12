package com.adept.api.workspace.dto;

import com.adept.api.common.validation.ValidTimezone;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
    @NotBlank @Size(max = 160) String name,
    @NotBlank @ValidTimezone String timezone
) {
}
