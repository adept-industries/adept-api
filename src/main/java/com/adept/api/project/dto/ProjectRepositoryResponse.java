package com.adept.api.project.dto;

import java.util.UUID;

import com.adept.api.integration.github.GitRepository;

public record ProjectRepositoryResponse(
    UUID id,
    String fullName,
    boolean trackingEnabled,
    boolean archived
) {
    public static ProjectRepositoryResponse from(GitRepository repository) {
        return new ProjectRepositoryResponse(
            repository.getId(),
            repository.getFullName(),
            repository.isTrackingEnabled(),
            repository.isArchived()
        );
    }
}
