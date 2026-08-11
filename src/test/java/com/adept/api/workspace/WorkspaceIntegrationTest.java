package com.adept.api.workspace;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.AuthService;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
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

class WorkspaceIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void workspaceEndpointsFlow() throws Exception {
        String email = uniqueEmail("ws-manager");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Workspace Manager", "Test Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf1 = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf1.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf1.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode loginBody = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginBody.get("accessToken").asText();

        // 1. GET /api/v1/workspaces
        mockMvc.perform(get("/api/v1/workspaces")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Test Workspace"))
            .andExpect(jsonPath("$[0].role").value("MANAGER"));

        // 2. GET /api/v1/workspaces/current
        mockMvc.perform(get("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(signup.workspace().id().toString()))
            .andExpect(jsonPath("$.name").value("Test Workspace"))
            .andExpect(jsonPath("$.timezone").value("UTC"))
            .andExpect(jsonPath("$.role").value("MANAGER"));

        // 3. PATCH /api/v1/workspaces/current - success
        CsrfPair csrfPatch1 = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", csrfPatch1.token())
                .cookie(new Cookie("XSRF-TOKEN", csrfPatch1.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Updated Workspace Name",
                        "timezone": "America/New_York"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Updated Workspace Name"))
            .andExpect(jsonPath("$.timezone").value("America/New_York"));

        // 4. Verify audit log entry
        int auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'WORKSPACE_UPDATED'",
            Integer.class
        );
        assertThat(auditCount).isGreaterThanOrEqualTo(1);

        // 5. PATCH /api/v1/workspaces/current - rejected with invalid timezone (400 problem response)
        CsrfPair csrfPatch2 = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", csrfPatch2.token())
                .cookie(new Cookie("XSRF-TOKEN", csrfPatch2.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "timezone": "Invalid/ZoneId_999"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        // 6. PATCH /api/v1/workspaces/current - rejected with explicit null name (400 problem response)
        CsrfPair csrfPatch3 = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", csrfPatch3.token())
                .cookie(new Cookie("XSRF-TOKEN", csrfPatch3.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": null
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        // 7. DELETE /api/v1/workspaces/current - success (202 Accepted)
        String currentSlug = signup.workspace().slug();
        CsrfPair csrfDelete1 = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", csrfDelete1.token())
                .cookie(new Cookie("XSRF-TOKEN", csrfDelete1.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "confirmationSlug": "%s",
                        "password": "%s"
                    }
                    """.formatted(currentSlug, VALID_PASSWORD)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.workspaceId").value(signup.workspace().id().toString()))
            .andExpect(jsonPath("$.status").value("DELETING"));

        // Verify workspace status in DB
        String statusInDb = jdbc.queryForObject(
            "SELECT status FROM workspaces WHERE id = ?",
            String.class,
            signup.workspace().id()
        );
        assertThat(statusInDb).isEqualTo(WorkspaceStatus.DELETING.name());

        // Verify processing job created in DB
        int jobCount = jdbc.queryForObject(
            "SELECT count(*) FROM processing_jobs WHERE job_type = 'DELETE_WORKSPACE'",
            Integer.class
        );
        assertThat(jobCount).isGreaterThanOrEqualTo(1);

        // Verify audit log recorded
        int deletionAuditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'WORKSPACE_DELETION_REQUESTED'",
            Integer.class
        );
        assertThat(deletionAuditCount).isGreaterThanOrEqualTo(1);

        // 8. DELETE /api/v1/workspaces/current - already deleting (409 Conflict)
        CsrfPair csrfDelete2 = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", csrfDelete2.token())
                .cookie(new Cookie("XSRF-TOKEN", csrfDelete2.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "confirmationSlug": "%s",
                        "password": "%s"
                    }
                    """.formatted(currentSlug, VALID_PASSWORD)))
            .andExpect(status().isConflict())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
