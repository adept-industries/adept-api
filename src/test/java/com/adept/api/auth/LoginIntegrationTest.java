package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
            .andReturn();

        assertThat(result.getResponse().getCookie("adept_refresh")).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM refresh_tokens WHERE user_id = ?",
            Integer.class,
            signup.user().id()
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_FAILED'",
            Integer.class
        )).isOne();
    }

    @Test
    void allInvalidCredentialFormsReturnOneSafeFailure() throws Exception {
        for (LoginRequest candidate : List.of(
            new LoginRequest("not-an-email", VALID_PASSWORD),
            new LoginRequest("x".repeat(321) + "@example.com", VALID_PASSWORD),
            new LoginRequest(uniqueEmail("short-password"), "short"),
            new LoginRequest(uniqueEmail("blocked-password"), "password"),
            new LoginRequest(uniqueEmail("oversized-password"), "x".repeat(73)),
            new LoginRequest("", "")
        )) {
            CsrfPair candidateCsrf = fetchCsrf(mockMvc);
            mockMvc.perform(post("/api/v1/auth/login")
                    .header("Origin", FRONTEND_ORIGIN)
                    .header("X-XSRF-TOKEN", candidateCsrf.token())
                    .cookie(candidateCsrf.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "email": "%s",
                            "password": "%s"
                        }
                        """.formatted(candidate.email(), candidate.password())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("The email or password is incorrect."));
        }

        String email = uniqueEmail("login-disabled");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Disabled User", "Disabled Workspace", "UTC"),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now(), status = 'DISABLED' WHERE id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);

        // 1. Unknown email
        MvcResult unknownResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "nonexistent-user-12345@example.com",
                        "password": "wrong-password-12345"
                    }
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.title").value("Sign in failed"))
            .andReturn();

        // 2. Wrong password for known user
        MvcResult wrongPassResult = mockMvc.perform(post("/api/v1/auth/login")
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
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.title").value("Sign in failed"))
            .andReturn();

        // 3. Disabled / suspended user with correct password
        MvcResult disabledResult = mockMvc.perform(post("/api/v1/auth/login")
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
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.title").value("Sign in failed"))
            .andReturn();

        // Verify all 3 cases return identical problem code, title, and detail
        for (MvcResult res : List.of(unknownResult, wrongPassResult, disabledResult)) {
            assertThat(res.getResponse().getStatus()).isEqualTo(401);
            assertThat(res.getResponse().getContentAsString()).contains("\"code\":\"INVALID_CREDENTIALS\"");
            assertThat(res.getResponse().getContentAsString()).contains("\"title\":\"Sign in failed\"");
            assertThat(res.getResponse().getContentAsString()).contains("\"detail\":\"The email or password is incorrect.\"");
        }

        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_FAILED'",
            Integer.class
        );
        assertThat(auditCount).isGreaterThanOrEqualTo(3);

        String metadata = jdbc.queryForObject(
            "SELECT metadata::text FROM audit_logs WHERE action = 'LOGIN_FAILED' LIMIT 1",
            String.class
        );
        assertThat(metadata).contains("emailHmac");
        assertThat(metadata).doesNotContain(email);
    }

    @Test
    void verifiedLoginReplacesAStaleCookieWithACompleteSession() throws Exception {
        String email = uniqueEmail("login-verified");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Verified User", "Single Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);
        Cookie staleCookie = new Cookie("adept_refresh", "stale-expired-garbage-cookie");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie(), staleCookie)
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
