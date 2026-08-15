package com.adept.api.integration.jira;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JiraProjectRepository extends JpaRepository<JiraProject, UUID> {

    Optional<JiraProject> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<JiraProject> findByWorkspaceIdAndProjectKey(UUID workspaceId, String projectKey);

    Optional<JiraProject> findByJiraIntegrationIdAndJiraProjectId(UUID jiraIntegrationId, String jiraProjectId);

    List<JiraProject> findAllByWorkspaceId(UUID workspaceId);

    List<JiraProject> findAllByWorkspaceIdAndTrackingEnabledTrue(UUID workspaceId);

    List<JiraProject> findAllByJiraIntegrationId(UUID jiraIntegrationId);

    List<JiraProject> findAllByIdInAndWorkspaceId(Collection<UUID> ids, UUID workspaceId);

    int countByJiraIntegrationId(UUID jiraIntegrationId);
}
