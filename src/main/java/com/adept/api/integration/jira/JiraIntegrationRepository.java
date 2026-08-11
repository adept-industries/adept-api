package com.adept.api.integration.jira;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.adept.api.common.domain.IntegrationStatus;

public interface JiraIntegrationRepository extends JpaRepository<JiraIntegration, UUID> {

    List<JiraIntegration> findAllByWorkspaceIdAndStatus(UUID workspaceId, IntegrationStatus status);
}
