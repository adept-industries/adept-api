package com.adept.api.auth;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.WorkspaceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceDeletionIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successfulWorkspaceDeletionFlow() throws Exception {
        String email = uniqueEmail("del-manager");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Deletion Manager", "Deletion Target Workspace", "UTC"),
            requestContext()
        );
        UUID workspaceId = signup.workspace().id();
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        // Insert active GitHub integration
        UUID ghId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO github_integrations (id, workspace_id, installation_id, account_external_id, account_login, account_type, repository_selection, status, created_at, updated_at, version)
            VALUES (?, ?, ?, 100, 'test-gh-org', 'ORGANIZATION', 'ALL', 'ACTIVE', now(), now(), 0)
            """, ghId, workspaceId, System.currentTimeMillis());

        // Insert active Jira integration
        UUID jiraId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO jira_integrations (id, workspace_id, cloud_id, site_url, display_name, access_token_enc, refresh_token_enc, encryption_key_version, access_token_expires_at, status, created_at, updated_at, version)
            VALUES (?, ?, ?, 'https://test.atlassian.net', 'Test Jira', 'enc_acc', 'enc_ref', 1, now() + interval '1 hour', 'ACTIVE', now(), now(), 0)
            """, jiraId, workspaceId, "cloud-" + UUID.randomUUID());

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();
        String confirmationSlug = signup.workspace().slug();

        // Perform DELETE request
        CsrfPair delCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", delCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", delCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "confirmationSlug": "%s",
                        "password": "%s"
                    }
                    """.formatted(confirmationSlug, VALID_PASSWORD)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.workspaceId").value(workspaceId.toString()))
            .andExpect(jsonPath("$.status").value("DELETING"));

        // Assert workspace status is DELETING
        String wsStatus = jdbc.queryForObject("SELECT status FROM workspaces WHERE id = ?", String.class, workspaceId);
        assertThat(wsStatus).isEqualTo(WorkspaceStatus.DELETING.name());

        // Assert GitHub integration is SUSPENDED and suspended_at is populated
        String ghStatus = jdbc.queryForObject("SELECT status FROM github_integrations WHERE id = ?", String.class, ghId);
        Object ghSuspendedAt = jdbc.queryForObject("SELECT suspended_at FROM github_integrations WHERE id = ?", Object.class, ghId);
        assertThat(ghStatus).isEqualTo("SUSPENDED");
        assertThat(ghSuspendedAt).isNotNull();

        // Assert Jira integration is SUSPENDED
        String jiraStatus = jdbc.queryForObject("SELECT status FROM jira_integrations WHERE id = ?", String.class, jiraId);
        assertThat(jiraStatus).isEqualTo("SUSPENDED");

        // Assert processing_jobs has one DELETE_WORKSPACE job with NULL workspace_id, repository_id, raw_event_id
        Integer jobCount = jdbc.queryForObject(
            "SELECT count(*) FROM processing_jobs WHERE job_type = 'DELETE_WORKSPACE' AND workspace_id IS NULL AND repository_id IS NULL AND raw_event_id IS NULL",
            Integer.class
        );
        assertThat(jobCount).isEqualTo(1);
    }

    @Test
    void invalidConfirmationSlugReturns400() throws Exception {
        String email = uniqueEmail("invalid-slug-user");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Slug User", "Slug Workspace", "UTC"),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();

        CsrfPair delCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", delCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", delCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "confirmationSlug": "wrong-slug-999",
                        "password": "%s"
                    }
                    """.formatted(VALID_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidPasswordReturns403ReauthenticationFailed() throws Exception {
        String email = uniqueEmail("invalid-pwd-user");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Password User", "Password Workspace", "UTC"),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asText();

        CsrfPair delCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", delCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", delCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "confirmationSlug": "%s",
                        "password": "wrong-password-123"
                    }
                    """.formatted(signup.workspace().slug())))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_FAILED"));
    }
}
