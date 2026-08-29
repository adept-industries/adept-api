package com.adept.api.project;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.AuthService;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectControllerIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void managerGroupsRepositoriesAndOnlyAssignedLeadCanSeeTheProject() throws Exception {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail("project-manager"),
                VALID_PASSWORD,
                "Project Manager",
                "Engineering",
                "UTC"
            ),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        UUID managerMembershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ?",
            UUID.class,
            signup.workspace().id(),
            signup.user().id()
        );
        String managerToken = jwtService.issue(new AuthenticatedPrincipal(
            signup.user().id(),
            managerMembershipId,
            signup.workspace().id(),
            MembershipRole.MANAGER,
            0
        ));

        CsrfPair createCsrf = fetchCsrf(mockMvc);
        MvcResult createResult = mockMvc.perform(post("/api/v1/projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", createCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", createCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Delivery Platform","description":"Frontend and backend delivery services"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Delivery Platform"))
            .andExpect(jsonPath("$.repositories").isEmpty())
            .andReturn();
        UUID projectId = UUID.fromString(body(createResult).path("id").asText());

        UUID repositoryId = insertRepository(signup.workspace().id(), managerMembershipId);
        CsrfPair repositoryCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/repositories")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", repositoryCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", repositoryCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repositoryIds":["%s"]}
                    """.formatted(repositoryId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.repositories[0].id").value(repositoryId.toString()));

        UUID jiraProjectId = insertJiraProject(signup.workspace().id(), managerMembershipId, true);
        jdbc.update("""
            INSERT INTO repository_jira_projects (repository_id, jira_project_id)
            VALUES (?, ?)
            """, repositoryId, jiraProjectId);

        UUID assignedLeadMembershipId = insertLead(signup.workspace().id(), "assigned-lead");
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """, signup.workspace().id(), repositoryId, assignedLeadMembershipId, managerMembershipId);
        String assignedLeadToken = tokenForLead(assignedLeadMembershipId, signup.workspace().id());

        mockMvc.perform(get("/api/v1/projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + assignedLeadToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(projectId.toString()))
            .andExpect(jsonPath("$[0].jiraProjects[0].id").value(jiraProjectId.toString()))
            .andExpect(jsonPath("$[0].repositories[0].id").value(repositoryId.toString()))
            .andExpect(jsonPath("$[0].repositories[0].jiraProjects[0].id").value(jiraProjectId.toString()));

        UUID unassignedLeadMembershipId = insertLead(signup.workspace().id(), "unassigned-lead");
        String unassignedLeadToken = tokenForLead(unassignedLeadMembershipId, signup.workspace().id());
        MvcResult hiddenResult = mockMvc.perform(get("/api/v1/projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + unassignedLeadToken))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(body(hiddenResult).size()).isZero();
    }

    @Test
    void managerCreatesProjectWithRepositoryAndJiraMappingAtomically() throws Exception {
        ManagerFixture manager = createManager("configured-project-manager");
        UUID repositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), true, false);
        UUID jiraProjectId = insertJiraProject(manager.workspaceId(), manager.membershipId(), true);

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Configured Platform",
                      "description": "Created with repository settings",
                      "repositories": [
                        {
                          "repositoryId": "%s",
                          "jiraProjectIds": ["%s"]
                        }
                      ]
                    }
                    """.formatted(repositoryId, jiraProjectId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.repositories.length()").value(1))
            .andExpect(jsonPath("$.repositories[0].id").value(repositoryId.toString()))
            .andExpect(jsonPath("$.repositories[0].jiraProjects.length()").value(1))
            .andExpect(jsonPath("$.repositories[0].jiraProjects[0].id").value(jiraProjectId.toString()))
            .andExpect(jsonPath("$.repositories[0].jiraProjects[0].trackingEnabled").value(true))
            .andReturn();

        UUID projectId = UUID.fromString(body(result).path("id").asText());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM project_repositories WHERE project_id = ? AND repository_id = ?",
            Integer.class,
            projectId,
            repositoryId
        )).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM repository_jira_projects WHERE repository_id = ? AND jira_project_id = ?",
            Integer.class,
            repositoryId,
            jiraProjectId
        )).isOne();
    }

    @Test
    void managerMapsTrackedJiraProjectsDirectlyToAnAdeptProject() throws Exception {
        ManagerFixture manager = createManager("project-jira-manager");
        UUID repositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), true, false);
        UUID trackedJiraProjectId = insertJiraProject(manager.workspaceId(), manager.membershipId(), true);
        UUID untrackedJiraProjectId = insertJiraProject(manager.workspaceId(), manager.membershipId(), false);

        CsrfPair createCsrf = fetchCsrf(mockMvc);
        MvcResult result = mockMvc.perform(post("/api/v1/projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", createCsrf.token())
                .cookie(createCsrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Project-level Jira",
                      "repositories":[{"repositoryId":"%s","jiraProjectIds":[]}],
                      "jiraProjectIds":["%s"]
                    }
                    """.formatted(repositoryId, trackedJiraProjectId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.repositories[0].id").value(repositoryId.toString()))
            .andExpect(jsonPath("$.repositories[0].jiraProjects[0].id")
                .value(trackedJiraProjectId.toString()))
            .andExpect(jsonPath("$.jiraProjects[0].id").value(trackedJiraProjectId.toString()))
            .andReturn();
        UUID projectId = UUID.fromString(body(result).path("id").asText());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM project_jira_projects WHERE project_id = ? AND jira_project_id = ?",
            Integer.class, projectId, trackedJiraProjectId
        )).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM repository_jira_projects WHERE repository_id = ?",
            Integer.class, repositoryId
        )).isZero();

        UUID leadMembershipId = insertLead(manager.workspaceId(), "project-jira-lead");
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """, manager.workspaceId(), repositoryId, leadMembershipId, manager.membershipId());
        mockMvc.perform(get("/api/v1/projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + tokenForLead(leadMembershipId, manager.workspaceId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].repositories[0].id").value(repositoryId.toString()))
            .andExpect(jsonPath("$[0].jiraProjects[0].id").value(trackedJiraProjectId.toString()));

        CsrfPair updateCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/configuration")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", updateCsrf.token())
                .cookie(updateCsrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repositories":[],"jiraProjectIds":["%s"]}
                    """.formatted(untrackedJiraProjectId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM project_jira_projects WHERE project_id = ? AND jira_project_id = ?",
            Integer.class, projectId, trackedJiraProjectId
        )).isOne();

        jdbc.update("UPDATE jira_projects SET tracking_enabled = false WHERE id = ?", trackedJiraProjectId);
        mockMvc.perform(get("/api/v1/projects/" + projectId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jiraProjects").isEmpty())
            .andExpect(jsonPath("$.repositories[0].jiraProjects").isEmpty());
    }

    @Test
    void repositoryAttachmentRejectsUntrackedAndArchivedRepositories() throws Exception {
        ManagerFixture manager = createManager("invalid-repository-manager");
        UUID projectId = insertProject(manager.workspaceId(), manager.membershipId(), "Repository Validation");
        UUID untrackedRepositoryId = insertRepository(
            manager.workspaceId(), manager.membershipId(), false, false
        );
        UUID archivedRepositoryId = insertRepository(
            manager.workspaceId(), manager.membershipId(), true, true
        );

        CsrfPair untrackedCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/repositories")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", untrackedCsrf.token())
                .cookie(untrackedCsrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repositoryIds":["%s"]}
                    """.formatted(untrackedRepositoryId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        CsrfPair archivedCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/configuration")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", archivedCsrf.token())
                .cookie(archivedCsrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositories": [
                        {"repositoryId":"%s","jiraProjectIds":[]}
                      ]
                    }
                    """.formatted(archivedRepositoryId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM project_repositories WHERE project_id = ?",
            Integer.class,
            projectId
        )).isZero();
    }

    @Test
    void managerReplacesProjectRepositoriesAndJiraMappingsTogether() throws Exception {
        ManagerFixture manager = createManager("replace-configuration-manager");
        UUID projectId = insertProject(manager.workspaceId(), manager.membershipId(), "Replace Configuration");
        UUID repositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), true, false);
        UUID removedRepositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), true, false);
        UUID oldJiraProjectId = insertJiraProject(manager.workspaceId(), manager.membershipId(), true);
        UUID newJiraProjectId = insertJiraProject(manager.workspaceId(), manager.membershipId(), true);
        UUID removedRepositoryJiraProjectId = insertJiraProject(
            manager.workspaceId(), manager.membershipId(), true
        );
        jdbc.update("""
            INSERT INTO project_repositories (project_id, repository_id, workspace_id)
            VALUES (?, ?, ?)
            """, projectId, removedRepositoryId, manager.workspaceId());
        jdbc.update("""
            INSERT INTO repository_jira_projects (repository_id, jira_project_id)
            VALUES (?, ?)
            """, repositoryId, oldJiraProjectId);
        jdbc.update("""
            INSERT INTO repository_jira_projects (repository_id, jira_project_id)
            VALUES (?, ?)
            """, removedRepositoryId, removedRepositoryJiraProjectId);

        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/configuration")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositories": [
                        {
                          "repositoryId": "%s",
                          "jiraProjectIds": ["%s"]
                        }
                      ]
                    }
                    """.formatted(repositoryId, newJiraProjectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.repositories.length()").value(1))
            .andExpect(jsonPath("$.repositories[0].id").value(repositoryId.toString()))
            .andExpect(jsonPath("$.repositories[0].jiraProjects.length()").value(1))
            .andExpect(jsonPath("$.repositories[0].jiraProjects[0].id").value(newJiraProjectId.toString()));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM repository_jira_projects WHERE repository_id = ? AND jira_project_id = ?",
            Integer.class,
            repositoryId,
            oldJiraProjectId
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM repository_jira_projects WHERE repository_id = ? AND jira_project_id = ?",
            Integer.class,
            repositoryId,
            newJiraProjectId
        )).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM project_repositories WHERE project_id = ? AND repository_id = ?",
            Integer.class,
            projectId,
            removedRepositoryId
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM repository_jira_projects WHERE repository_id = ? AND jira_project_id = ?",
            Integer.class,
            removedRepositoryId,
            removedRepositoryJiraProjectId
        )).isOne();
    }

    @Test
    void crossWorkspaceJiraConfigurationRollsBackProjectCreation() throws Exception {
        ManagerFixture manager = createManager("atomic-project-manager");
        ManagerFixture otherManager = createManager("other-project-manager");
        UUID repositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), true, false);
        UUID otherWorkspaceJiraProjectId = insertJiraProject(
            otherManager.workspaceId(), otherManager.membershipId(), true
        );

        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Must Roll Back",
                      "repositories": [
                        {
                          "repositoryId": "%s",
                          "jiraProjectIds": ["%s"]
                        }
                      ]
                    }
                    """.formatted(repositoryId, otherWorkspaceJiraProjectId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("JIRA_PROJECT_NOT_FOUND"));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE workspace_id = ? AND name = 'Must Roll Back'",
            Integer.class,
            manager.workspaceId()
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM repository_jira_projects WHERE repository_id = ?",
            Integer.class,
            repositoryId
        )).isZero();
    }

    @Test
    void crossWorkspaceRepositoryCannotReplaceExistingConfiguration() throws Exception {
        ManagerFixture manager = createManager("repository-scope-manager");
        ManagerFixture otherManager = createManager("other-repository-manager");
        UUID projectId = insertProject(manager.workspaceId(), manager.membershipId(), "Scoped Configuration");
        UUID existingRepositoryId = insertRepository(
            manager.workspaceId(), manager.membershipId(), true, false
        );
        UUID otherWorkspaceRepositoryId = insertRepository(
            otherManager.workspaceId(), otherManager.membershipId(), true, false
        );
        jdbc.update("""
            INSERT INTO project_repositories (project_id, repository_id, workspace_id)
            VALUES (?, ?, ?)
            """, projectId, existingRepositoryId, manager.workspaceId());

        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/configuration")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositories": [
                        {"repositoryId":"%s","jiraProjectIds":[]}
                      ]
                    }
                    """.formatted(otherWorkspaceRepositoryId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM project_repositories WHERE project_id = ? AND repository_id = ?",
            Integer.class,
            projectId,
            existingRepositoryId
        )).isOne();
    }

    @Test
    void leadCannotReplaceProjectConfiguration() throws Exception {
        ManagerFixture manager = createManager("lead-configuration-manager");
        UUID repositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), true, false);
        UUID projectId = insertProject(manager.workspaceId(), manager.membershipId(), "Protected Configuration");
        jdbc.update("""
            INSERT INTO project_repositories (project_id, repository_id, workspace_id)
            VALUES (?, ?, ?)
            """, projectId, repositoryId, manager.workspaceId());

        UUID leadMembershipId = insertLead(manager.workspaceId(), "configuration-lead");
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """, manager.workspaceId(), repositoryId, leadMembershipId, manager.membershipId());
        String leadToken = tokenForLead(leadMembershipId, manager.workspaceId());

        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/configuration")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadToken)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"repositories":[]}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MANAGER_REQUIRED"));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM project_repositories WHERE project_id = ? AND repository_id = ?",
            Integer.class,
            projectId,
            repositoryId
        )).isOne();
    }

    private UUID insertRepository(UUID workspaceId, UUID managerMembershipId) {
        return insertRepository(workspaceId, managerMembershipId, true, false);
    }

    private UUID insertRepository(
            UUID workspaceId,
            UUID managerMembershipId,
            boolean trackingEnabled,
            boolean archived) {
        UUID installationId = UUID.randomUUID();
        long suffix = Math.abs(installationId.getMostSignificantBits());
        String repositoryName = "repo-" + installationId.toString().substring(0, 8);
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, ?, ?, 'adept-project-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, suffix, suffix + 1, managerMembershipId);
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, archived
            ) VALUES (?, ?, ?, 'adept-project-test', ?, ?, 'main', 'PRIVATE', ?, ?)
            RETURNING id
            """, UUID.class,
            workspaceId,
            integrationId,
            suffix + 2,
            repositoryName,
            "adept-project-test/" + repositoryName,
            trackingEnabled,
            archived);
    }

    private ManagerFixture createManager(String prefix) {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail(prefix),
                VALID_PASSWORD,
                "Project Manager",
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
            signup.user().id(),
            membershipId,
            signup.workspace().id(),
            MembershipRole.MANAGER,
            0
        ));
        return new ManagerFixture(signup.workspace().id(), membershipId, token);
    }

    private UUID insertProject(UUID workspaceId, UUID managerMembershipId, String name) {
        return jdbc.queryForObject("""
            INSERT INTO projects (workspace_id, name, created_by_membership_id)
            VALUES (?, ?, ?)
            RETURNING id
            """, UUID.class, workspaceId, name, managerMembershipId);
    }

    private UUID insertJiraProject(UUID workspaceId, UUID managerMembershipId, boolean trackingEnabled) {
        UUID suffix = UUID.randomUUID();
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO jira_integrations (
                workspace_id, cloud_id, site_url, display_name, access_token_enc,
                refresh_token_enc, encryption_key_version, access_token_expires_at,
                scopes, status, connected_by_membership_id
            ) VALUES (?, ?, 'https://adept-test.atlassian.net', 'Adept Test',
                'encrypted-access', 'encrypted-refresh', 1, now() + interval '1 hour',
                ARRAY['read:jira-work'], 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, "cloud-" + suffix, managerMembershipId);
        return jdbc.queryForObject("""
            INSERT INTO jira_projects (
                workspace_id, jira_integration_id, jira_project_id, project_key,
                project_name, project_type, tracking_enabled
            ) VALUES (?, ?, ?, ?, ?, 'software', ?)
            RETURNING id
            """, UUID.class,
            workspaceId,
            integrationId,
            "jira-" + suffix,
            "KEY" + suffix.toString().substring(0, 6).replace("-", ""),
            "Jira Project " + suffix.toString().substring(0, 8),
            trackingEnabled);
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

    private String tokenForLead(UUID membershipId, UUID workspaceId) {
        UUID userId = jdbc.queryForObject(
            "SELECT user_id FROM memberships WHERE id = ?",
            UUID.class,
            membershipId
        );
        return jwtService.issue(new AuthenticatedPrincipal(
            userId,
            membershipId,
            workspaceId,
            MembershipRole.LEAD,
            0
        ));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record ManagerFixture(UUID workspaceId, UUID membershipId, String token) {
    }
}
