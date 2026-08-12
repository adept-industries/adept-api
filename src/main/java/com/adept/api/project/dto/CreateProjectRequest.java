package com.adept.api.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
    @NotBlank @Size(max = 160) String name,
    @Size(max = 1000) String description
) {
}
