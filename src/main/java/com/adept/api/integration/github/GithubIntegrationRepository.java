package com.adept.api.integration.github;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.adept.api.common.domain.IntegrationStatus;

public interface GithubIntegrationRepository extends JpaRepository<GithubIntegration, UUID> {

    List<GithubIntegration> findAllByWorkspaceIdAndStatus(UUID workspaceId, IntegrationStatus status);
}
