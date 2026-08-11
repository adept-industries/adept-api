package com.adept.api.auth;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.domain.WorkspaceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceControllerIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String loginAndGetAccessToken(String email, String password, UUID targetWorkspaceId) throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        if (body.has("accessToken") && !body.get("accessToken").isNull()) {
            return body.get("accessToken").asText();
        }

        Cookie refreshCookie = result.getResponse().getCookie("adept_refresh");
        CsrfPair switchCsrf = fetchCsrf(mockMvc);
        MvcResult switchResult = mockMvc.perform(post("/api/v1/auth/switch-workspace/" + targetWorkspaceId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", switchCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", switchCsrf.token()))
                .cookie(refreshCookie))
            .andExpect(status().isOk())
            .andReturn();

        return objectMapper.readTree(switchResult.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void listWorkspacesReturnsOnlyActiveCallerMemberships() throws Exception {
        String email = uniqueEmail("active-workspaces-user");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Multi Workspace User", "Workspace One", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        // Create second workspace with ACTIVE membership
        UUID ws2Id = UUID.randomUUID();
        UUID mem2Id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO workspaces (id, name, slug, timezone, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, now(), now(), 0)",
            ws2Id, "Workspace Two", "workspace-two-" + UUID.randomUUID().toString().substring(0, 8), "UTC", WorkspaceStatus.ACTIVE.name()
        );
        jdbc.update(
            "INSERT INTO memberships (id, user_id, workspace_id, role, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, now(), now(), 0)",
            mem2Id, signup.user().id(), ws2Id, MembershipRole.LEAD.name(), MembershipStatus.ACTIVE.name()
        );

        // Create third workspace with SUSPENDED membership
        UUID ws3Id = UUID.randomUUID();
        UUID mem3Id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO workspaces (id, name, slug, timezone, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, now(), now(), 0)",
            ws3Id, "Workspace Three", "workspace-three-" + UUID.randomUUID().toString().substring(0, 8), "UTC", WorkspaceStatus.ACTIVE.name()
        );
        jdbc.update(
            "INSERT INTO memberships (id, user_id, workspace_id, role, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, now(), now(), 0)",
            mem3Id, signup.user().id(), ws3Id, MembershipRole.LEAD.name(), MembershipStatus.SUSPENDED.name()
        );

        // Perform login and switch workspace if required to get access token
        String accessToken = loginAndGetAccessToken(email, VALID_PASSWORD, signup.workspace().id());

        // Perform GET /api/v1/workspaces
        MvcResult wsListResult = mockMvc.perform(get("/api/v1/workspaces")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode wsArray = objectMapper.readTree(wsListResult.getResponse().getContentAsString());
        assertThat(wsArray.isArray()).isTrue();
        assertThat(wsArray.size()).isEqualTo(2);

        boolean foundWs1 = false;
        boolean foundWs2 = false;
        boolean foundWs3 = false;

        for (JsonNode node : wsArray) {
            String name = node.get("name").asText();
            if ("Workspace One".equals(name)) foundWs1 = true;
            if ("Workspace Two".equals(name)) foundWs2 = true;
            if ("Workspace Three".equals(name)) foundWs3 = true;
        }

        assertThat(foundWs1).isTrue();
        assertThat(foundWs2).isTrue();
        assertThat(foundWs3).isFalse();
    }

    @Test
    void getCurrentWorkspaceDerivesStateFromPrincipal() throws Exception {
        String email = uniqueEmail("current-ws-user");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Current User", "Primary Workspace", "Europe/London"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        String accessToken = loginAndGetAccessToken(email, VALID_PASSWORD, signup.workspace().id());

        mockMvc.perform(get("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(signup.workspace().id().toString()))
            .andExpect(jsonPath("$.name").value("Primary Workspace"))
            .andExpect(jsonPath("$.timezone").value("Europe/London"))
            .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    void managerCanUpdateNameAndTimezoneAndSlugRemainsUnchanged() throws Exception {
        String email = uniqueEmail("manager-patch-user");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Manager User", "Original Workspace Name", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        String accessToken = loginAndGetAccessToken(email, VALID_PASSWORD, signup.workspace().id());
        String initialSlug = signup.workspace().slug();

        CsrfPair patchCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", patchCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", patchCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Updated Manager Workspace",
                        "timezone": "Asia/Tokyo"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated Manager Workspace"))
            .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"))
            .andExpect(jsonPath("$.slug").value(initialSlug));

        String slugInDb = jdbc.queryForObject(
            "SELECT slug FROM workspaces WHERE id = ?",
            String.class,
            signup.workspace().id()
        );
        assertThat(slugInDb).isEqualTo(initialSlug);

        String auditMetadata = jdbc.queryForObject(
            "SELECT metadata::text FROM audit_logs WHERE workspace_id = ? AND action = 'WORKSPACE_UPDATED'",
            String.class,
            signup.workspace().id()
        );
        JsonNode metadata = objectMapper.readTree(auditMetadata);
        assertThat(metadata.path("changedFields").toString())
            .isEqualTo("[\"name\",\"timezone\"]");
        assertThat(auditMetadata)
            .doesNotContain("Original Workspace Name")
            .doesNotContain("Updated Manager Workspace");
    }

    @Test
    void leadReceives403ManagerRequiredOnPatchOrDelete() throws Exception {
        String managerEmail = uniqueEmail("ws-owner");
        SignupResponse signup = authService.signup(
            new SignupRequest(managerEmail, VALID_PASSWORD, "Workspace Owner", "Team Workspace", "UTC"),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        // Create a Lead user in the same workspace
        String leadEmail = uniqueEmail("lead-user");
        UUID leadUserId = UUID.randomUUID();
        UUID leadMemId = UUID.randomUUID();
        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, signup.user().id());
        jdbc.update(
            "INSERT INTO users (id, email, display_name, password_hash, status, email_verified_at, token_version, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, now(), 1, now(), now(), 0)",
            leadUserId, leadEmail, "Lead User", passwordHash, "ACTIVE"
        );

        jdbc.update(
            "INSERT INTO memberships (id, user_id, workspace_id, role, status, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, now(), now(), 0)",
            leadMemId, leadUserId, signup.workspace().id(), MembershipRole.LEAD.name(), MembershipStatus.ACTIVE.name()
        );

        String leadAccessToken = loginAndGetAccessToken(leadEmail, VALID_PASSWORD, signup.workspace().id());

        // 1. Lead PATCH -> 403 MANAGER_REQUIRED
        CsrfPair patchCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAccessToken)
                .header("X-XSRF-TOKEN", patchCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", patchCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Unauthorized Lead Update"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("MANAGER_REQUIRED"));

        // 2. Lead DELETE -> 403 MANAGER_REQUIRED
        CsrfPair deleteCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAccessToken)
                .header("X-XSRF-TOKEN", deleteCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", deleteCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "confirmationSlug": "%s",
                        "password": "%s"
                    }
                    """.formatted(signup.workspace().slug(), VALID_PASSWORD)))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("MANAGER_REQUIRED"));

        Integer successAuditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE workspace_id = ? "
                + "AND action IN ('WORKSPACE_UPDATED', 'WORKSPACE_DELETION_REQUESTED')",
            Integer.class,
            signup.workspace().id()
        );
        assertThat(successAuditCount).isZero();
    }

    @Test
    void patchValidationErrors() throws Exception {
        String email = uniqueEmail("validation-patch-user");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Validation User", "Valid Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        String accessToken = loginAndGetAccessToken(email, VALID_PASSWORD, signup.workspace().id());

        // Representative invalid shapes: missing fields, explicit null, and invalid ZoneId.
        CsrfPair c1 = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", c1.token())
                .cookie(new Cookie("XSRF-TOKEN", c1.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        CsrfPair c2 = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", c2.token())
                .cookie(new Cookie("XSRF-TOKEN", c2.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": null}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        CsrfPair c3 = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", c3.token())
                .cookie(new Cookie("XSRF-TOKEN", c3.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\": \"Invalid/Zone_999\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        Integer updateAuditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE workspace_id = ? AND action = 'WORKSPACE_UPDATED'",
            Integer.class,
            signup.workspace().id()
        );
        assertThat(updateAuditCount).isZero();
    }

}
