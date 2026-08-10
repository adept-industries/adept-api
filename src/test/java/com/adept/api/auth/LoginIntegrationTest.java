package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.dto.LoginRequest;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;

import jakarta.servlet.http.Cookie;

class LoginIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginWithUnverifiedEmailFailsWith403EmailNotVerified() throws Exception {
        String email = uniqueEmail("login-unverified");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Unverified User", "Unverified Workspace", "UTC"),
            requestContext()
        );

        CsrfPair csrf = fetchCsrf(mockMvc);
        LoginRequest loginRequest = new LoginRequest(email, VALID_PASSWORD);

        mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void loginWithInvalidCredentialsFailsWith401AndLogsAudit() throws Exception {
        String email = uniqueEmail("login-invalid");
        CsrfPair csrf = fetchCsrf(mockMvc);

        mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "wrong-password-12345"
                    }
                    """.formatted(email)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_FAILED'",
            Integer.class
        );
        assertThat(auditCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void loginWithVerifiedSingleWorkspaceUserReturnsSessionAndSetsCookie() throws Exception {
        String email = uniqueEmail("login-verified");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Verified User", "Single Workspace", "UTC"),
            requestContext()
        );

        // Manually mark email verified
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.expiresInSeconds").value(900))
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(false))
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.currentMembership.workspaceName").value("Single Workspace"))
            .andExpect(jsonPath("$.workspaces[0].name").value("Single Workspace"))
            .andReturn();

        Cookie refreshCookie = result.getResponse().getCookie("adept_refresh");
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.isHttpOnly()).isTrue();
        assertThat(refreshCookie.getPath()).isEqualTo("/api/v1/auth");

        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_SUCCEEDED'",
            Integer.class
        );
        assertThat(auditCount).isGreaterThanOrEqualTo(1);

        Integer refreshCount = jdbc.queryForObject(
            "SELECT count(*) FROM refresh_tokens WHERE user_id = ?",
            Integer.class,
            signup.user().id()
        );
        assertThat(refreshCount).isEqualTo(1);
    }
}
