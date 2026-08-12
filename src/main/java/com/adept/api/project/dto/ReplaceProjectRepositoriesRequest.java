package com.adept.api.project.dto;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplaceProjectRepositoriesRequest(
    @NotNull @Size(max = 500) Set<@Valid @NotNull UUID> repositoryIds
) {
}
