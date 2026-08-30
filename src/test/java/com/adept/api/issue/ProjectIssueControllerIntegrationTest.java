package com.adept.api.issue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.adept.api.auth.AuthService;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectIssueControllerIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managerAndLeadReceiveTheAgreedGithubAndJiraScopes() throws Exception {
        ManagerFixture manager = createManager("issue-scope-manager");
        UUID projectId = insertProject(manager, "Issue Queue");
        UUID assignedRepositoryId = insertRepository(manager, "assigned-api");
        UUID unassignedRepositoryId = insertRepository(manager, "manager-ui");
        attachRepository(projectId, assignedRepositoryId, manager.workspaceId());
        attachRepository(projectId, unassignedRepositoryId, manager.workspaceId());

        UUID leadMembershipId = insertLead(manager.workspaceId(), "issue-scope-lead");
        assignLead(manager, assignedRepositoryId, leadMembershipId);

        insertGithubIssue(
            manager.workspaceId(), assignedRepositoryId, 41, "Repair API authorization", "OPEN"
        );
        insertGithubIssue(
            manager.workspaceId(), unassignedRepositoryId, 99, "Correct dashboard state", "OPEN"
        );
        insertGithubIssue(
            manager.workspaceId(), assignedRepositoryId, 42, "Already completed", "CLOSED"
        );

        JiraFixture jira = insertJiraProject(manager);
        attachJiraProject(projectId, jira.projectId(), manager.workspaceId());
        insertJiraIssue(manager.workspaceId(), jira.projectId(), "OPS-12", "Investigate outage", false);
        insertJiraIssue(manager.workspaceId(), jira.projectId(), "OPS-11", "Resolved outage", true);

        String managerGithub = mockMvc.perform(get(
                "/api/v1/projects/" + projectId + "/issues/github")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.items[0].repositoryFullName").exists())
            .andExpect(jsonPath("$.items[0].url").value(
                org.hamcrest.Matchers.startsWith("https://github.com/")))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(managerGithub).doesNotContain("rawData", "secret issue body");

        String leadToken = tokenForLead(leadMembershipId, manager.workspaceId());
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/issues/github")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].repositoryId")
                .value(assignedRepositoryId.toString()))
            .andExpect(jsonPath("$.items[0].title").value("Repair API authorization"));

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/issues/jira")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].issueKey").value("OPS-12"))
            .andExpect(jsonPath("$.items[0].jiraProjectKey").value(jira.projectKey()))
            .andExpect(jsonPath("$.items[0].url")
                .value("https://adept-issues.atlassian.net/browse/OPS-12"));

        // Jira is mapped to the Adept project, not to an individual repository,
        // so a Lead who can read this project receives the same Jira issue set.
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/issues/jira")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].issueKey").value("OPS-12"));

        UUID unrelatedLead = insertLead(manager.workspaceId(), "unrelated-issue-lead");
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/issues/jira")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer "
                    + tokenForLead(unrelatedLead, manager.workspaceId())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void managerQueuesDeduplicatedGithubAndJiraIssueSynchronization() throws Exception {
        ManagerFixture manager = createManager("issue-sync-manager");
        UUID projectId = insertProject(manager, "Issue Sync");
        UUID firstRepositoryId = insertRepository(manager, "first-service");
        UUID secondRepositoryId = insertRepository(manager, "second-service");
        attachRepository(projectId, firstRepositoryId, manager.workspaceId());
        attachRepository(projectId, secondRepositoryId, manager.workspaceId());
        JiraFixture jira = insertJiraProject(manager);
        attachJiraProject(projectId, jira.projectId(), manager.workspaceId());

        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/issues/sync")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.queuedGithubRepositories").value(2))
            .andExpect(jsonPath("$.alreadyQueuedGithubRepositories").value(0))
            .andExpect(jsonPath("$.queuedJiraIntegrations").value(1))
            .andExpect(jsonPath("$.alreadyQueuedJiraIntegrations").value(0));

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM processing_jobs
            WHERE job_type = 'BACKFILL_REPOSITORY'
              AND payload ->> 'issuesOnly' = 'true'
            """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM processing_jobs
            WHERE job_type = 'SYNC_JIRA_PROJECTS'
              AND payload ->> 'issuesOnly' = 'true'
              AND payload ->> 'jiraIntegrationId' = ?
              AND payload -> 'jiraProjectIds' @> CAST(? AS jsonb)
            """, Integer.class,
            jira.integrationId().toString(),
            "[\"" + jira.projectId() + "\"]")).isEqualTo(1);

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/issues/sync")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.queuedGithubRepositories").value(0))
            .andExpect(jsonPath("$.alreadyQueuedGithubRepositories").value(2))
            .andExpect(jsonPath("$.queuedJiraIntegrations").value(0))
            .andExpect(jsonPath("$.alreadyQueuedJiraIntegrations").value(1));

        UUID leadMembershipId = insertLead(manager.workspaceId(), "issue-sync-lead");
        assignLead(manager, firstRepositoryId, leadMembershipId);
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/issues/sync")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer "
                    + tokenForLead(leadMembershipId, manager.workspaceId()))
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MANAGER_REQUIRED"));
    }

    @Test
    void paginationIsValidatedAndCrossWorkspaceProjectsAreHidden() throws Exception {
        ManagerFixture manager = createManager("issue-validation-manager");
        ManagerFixture other = createManager("other-issue-manager");
        UUID otherProjectId = insertProject(other, "Other Issues");

        mockMvc.perform(get("/api/v1/projects/" + otherProjectId + "/issues/github")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        UUID projectId = insertProject(manager, "Validated Issues");
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/issues/jira")
                .param("size", "101")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ManagerFixture createManager(String prefix) {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail(prefix),
                VALID_PASSWORD,
                "Issue Manager",
                prefix + " Workspace",
                "UTC"
            ),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        UUID membershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ?",
            UUID.class,
            signup.workspace().id(),
            signup.user().id()
        );
        String token = jwtService.issue(new AuthenticatedPrincipal(
            signup.user().id(), membershipId, signup.workspace().id(), MembershipRole.MANAGER, 0
        ));
        return new ManagerFixture(signup.workspace().id(), membershipId, token);
    }

    private UUID insertProject(ManagerFixture manager, String name) {
        return jdbc.queryForObject("""
            INSERT INTO projects (workspace_id, name, created_by_membership_id)
            VALUES (?, ?, ?)
            RETURNING id
            """, UUID.class, manager.workspaceId(), name, manager.membershipId());
    }

    private UUID insertRepository(ManagerFixture manager, String name) {
        long suffix = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, ?, ?, 'adept-issue-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, manager.workspaceId(), suffix, suffix + 1, manager.membershipId());
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, archived
            ) VALUES (?, ?, ?, 'adept-issue-test', ?, ?, 'main', 'PRIVATE', true, false)
            RETURNING id
            """, UUID.class,
            manager.workspaceId(), integrationId, suffix + 2, name, "adept-issue-test/" + name);
    }

    private JiraFixture insertJiraProject(ManagerFixture manager) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO jira_integrations (
                workspace_id, cloud_id, site_url, display_name, access_token_enc,
                refresh_token_enc, encryption_key_version, access_token_expires_at,
                scopes, status, connected_by_membership_id
            ) VALUES (?, ?, 'https://adept-issues.atlassian.net', 'Issue Test Jira',
                'encrypted-access', 'encrypted-refresh', 1, now() + interval '1 hour',
                ARRAY['read:jira-work'], 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, manager.workspaceId(), "cloud-" + suffix, manager.membershipId());
        String projectKey = "OPS" + suffix.substring(0, 4).toUpperCase();
        UUID projectId = jdbc.queryForObject("""
            INSERT INTO jira_projects (
                workspace_id, jira_integration_id, jira_project_id, project_key,
                project_name, project_type, tracking_enabled
            ) VALUES (?, ?, ?, ?, 'Operations', 'software', true)
            RETURNING id
            """, UUID.class,
            manager.workspaceId(), integrationId, "jira-" + suffix, projectKey);
        return new JiraFixture(integrationId, projectId, projectKey);
    }

    private void attachRepository(UUID projectId, UUID repositoryId, UUID workspaceId) {
        jdbc.update("""
            INSERT INTO project_repositories (project_id, repository_id, workspace_id)
            VALUES (?, ?, ?)
            """, projectId, repositoryId, workspaceId);
    }

    private void attachJiraProject(UUID projectId, UUID jiraProjectId, UUID workspaceId) {
        jdbc.update("""
            INSERT INTO project_jira_projects (project_id, jira_project_id, workspace_id)
            VALUES (?, ?, ?)
            """, projectId, jiraProjectId, workspaceId);
    }

    private void insertGithubIssue(
            UUID workspaceId,
            UUID repositoryId,
            int number,
            String title,
            String state) {
        jdbc.update("""
            INSERT INTO github_issues (
                workspace_id, repository_id, github_issue_id, github_node_id,
                number, title, state, author_login, assignee_logins, labels,
                comments_count, github_created_at, github_updated_at,
                last_synced_at, raw_data
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'octocat', ARRAY['reviewer'],
                ARRAY['bug'], 2, now() - interval '3 days', now(), now(),
                '{"body":"secret issue body"}'::jsonb)
            """,
            workspaceId,
            repositoryId,
            20_000L + number,
            "GI_" + number,
            number,
            title,
            state);
    }

    private void insertJiraIssue(
            UUID workspaceId,
            UUID jiraProjectId,
            String issueKey,
            String summary,
            boolean resolved) {
        jdbc.update("""
            INSERT INTO jira_issues (
                workspace_id, jira_project_id, jira_issue_id, issue_key,
                issue_type, status_name, priority_name, summary,
                jira_created_at, jira_updated_at, resolved_at, raw_data
            ) VALUES (?, ?, ?, ?, 'Bug', ?, 'High', ?,
                now() - interval '2 days', now(),
                CASE WHEN ? THEN now() ELSE NULL END,
                '{"description":"secret issue body"}'::jsonb)
            """,
            workspaceId,
            jiraProjectId,
            "JI_" + issueKey,
            issueKey,
            resolved ? "Done" : "In Progress",
            summary,
            resolved);
    }

    private UUID insertLead(UUID workspaceId, String prefix) {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status, email_verified_at,
                token_version, created_at, updated_at, version
            ) VALUES (?, ?, 'unused-test-hash', 'Lead', 'ACTIVE', now(), 0, now(), now(), 0)
            """, userId, uniqueEmail(prefix));
        jdbc.update("""
            INSERT INTO memberships (
                id, workspace_id, user_id, role, status, joined_at, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'LEAD', 'ACTIVE', now(), now(), now(), 0)
            """, membershipId, workspaceId, userId);
        return membershipId;
    }

    private void assignLead(
            ManagerFixture manager,
            UUID repositoryId,
            UUID leadMembershipId) {
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """,
            manager.workspaceId(), repositoryId, leadMembershipId, manager.membershipId());
    }

    private String tokenForLead(UUID membershipId, UUID workspaceId) {
        UUID userId = jdbc.queryForObject(
            "SELECT user_id FROM memberships WHERE id = ?", UUID.class, membershipId
        );
        return jwtService.issue(new AuthenticatedPrincipal(
            userId, membershipId, workspaceId, MembershipRole.LEAD, 0
        ));
    }

    private record ManagerFixture(UUID workspaceId, UUID membershipId, String token) {
    }

    private record JiraFixture(UUID integrationId, UUID projectId, String projectKey) {
    }
}
