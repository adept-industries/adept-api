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
            .andExpect(jsonPath("$[0].repositories[0].id").value(repositoryId.toString()));

        UUID unassignedLeadMembershipId = insertLead(signup.workspace().id(), "unassigned-lead");
        String unassignedLeadToken = tokenForLead(unassignedLeadMembershipId, signup.workspace().id());
        MvcResult hiddenResult = mockMvc.perform(get("/api/v1/projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + unassignedLeadToken))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(body(hiddenResult).size()).isZero();
    }

    private UUID insertRepository(UUID workspaceId, UUID managerMembershipId) {
        UUID installationId = UUID.randomUUID();
        long suffix = Math.abs(installationId.getMostSignificantBits());
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
                name, full_name, default_branch, visibility, tracking_enabled
            ) VALUES (?, ?, ?, 'adept-project-test', 'api', 'adept-project-test/api', 'main', 'PRIVATE', true)
            RETURNING id
            """, UUID.class, workspaceId, integrationId, suffix + 2);
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
}
