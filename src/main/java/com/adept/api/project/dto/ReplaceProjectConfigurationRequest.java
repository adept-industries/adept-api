package com.adept.api.project.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplaceProjectConfigurationRequest(
    @NotNull @Size(max = 500)
    List<@NotNull @Valid ProjectRepositoryConfigurationRequest> repositories
) {
}
