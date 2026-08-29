package com.adept.api.project.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
    @NotBlank @Size(max = 160) String name,
    @Size(max = 1000) String description,
    @Size(max = 500)
    List<@NotNull @Valid ProjectRepositoryConfigurationRequest> repositories
) {
    public CreateProjectRequest {
        repositories = repositories == null ? List.of() : repositories;
    }
}
