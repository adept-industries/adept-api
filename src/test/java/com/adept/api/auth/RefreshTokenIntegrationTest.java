package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;

import jakarta.servlet.http.Cookie;

class RefreshTokenIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void refreshTokenRotationSucceedsAndChildInheritsExactAbsoluteExpiry() throws Exception {
        String email = uniqueEmail("refresh-rotation");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Refresh User", "Refresh Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf1 = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf1.token())
                .cookie(csrf1.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        Cookie parentCookie = loginResult.getResponse().getCookie("adept_refresh");
        assertThat(parentCookie).isNotNull();

        CsrfPair csrf2 = fetchCsrf(mockMvc);
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie(), parentCookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(false))
            .andReturn();

        Cookie childCookie = refreshResult.getResponse().getCookie("adept_refresh");
        assertThat(childCookie).isNotNull();
        assertThat(childCookie.getValue()).isNotEqualTo(parentCookie.getValue());

        List<RefreshToken> tokens = refreshTokenRepository.findAll();
        assertThat(tokens).hasSize(2);

        RefreshToken parentRow = tokens.stream().filter(t -> t.getParentToken() == null).findFirst().orElseThrow();
        RefreshToken childRow = tokens.stream().filter(t -> t.getParentToken() != null).findFirst().orElseThrow();

        assertThat(parentRow.getRotatedAt()).isNotNull();
        assertThat(childRow.getRotatedAt()).isNull();
        assertThat(childRow.getFamilyId()).isEqualTo(parentRow.getFamilyId());
        assertThat(childRow.getExpiresAt()).isEqualTo(parentRow.getExpiresAt());
    }

    @Test
    void expiredRefreshTokenFailsSafelyAndClearsCookie() throws Exception {
        String email = uniqueEmail("refresh-expired");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Expired User", "Expired Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf1 = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf1.token())
                .cookie(csrf1.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        Cookie parentCookie = loginResult.getResponse().getCookie("adept_refresh");

        // Manually expire the token row in DB
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 hour'");

        CsrfPair csrf2 = fetchCsrf(mockMvc);
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie(), parentCookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"))
            .andReturn();

        Cookie clearedCookie = refreshResult.getResponse().getCookie("adept_refresh");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isEqualTo(0);
    }

    @Test
    void revokedRefreshTokenFailsSafelyAndClearsCookie() throws Exception {
        String email = uniqueEmail("refresh-revoked");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Revoked User", "Revoked Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf1 = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf1.token())
                .cookie(csrf1.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        Cookie parentCookie = loginResult.getResponse().getCookie("adept_refresh");

        // Manually revoke the token row in DB
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now()");

        CsrfPair csrf2 = fetchCsrf(mockMvc);
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie(), parentCookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"))
            .andReturn();

        Cookie clearedCookie = refreshResult.getResponse().getCookie("adept_refresh");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isEqualTo(0);
    }

    @Test
    void refreshTokenReuseDetectionRevokesFamilyAndIncrementsTokenVersion() throws Exception {
        String email = uniqueEmail("refresh-reuse");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Reuse User", "Reuse Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf1 = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf1.token())
                .cookie(csrf1.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s",
                        "password": "%s"
                    }
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        Cookie parentCookie = loginResult.getResponse().getCookie("adept_refresh");

        // Rotate once (valid)
        CsrfPair csrf2 = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie(), parentCookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());

        // Attempt to rotate parent AGAIN (Reuse Detected!)
        CsrfPair csrf3 = fetchCsrf(mockMvc);
        MvcResult reuseResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf3.token())
                .cookie(csrf3.cookie(), parentCookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REFRESH_REUSE_DETECTED"))
            .andReturn();

        Cookie clearedCookie = reuseResult.getResponse().getCookie("adept_refresh");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isEqualTo(0);

        Integer tokenVersion = jdbc.queryForObject(
            "SELECT token_version FROM users WHERE id = ?",
            Integer.class,
            signup.user().id()
        );
        assertThat(tokenVersion).isGreaterThan(0);

        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'REFRESH_REUSE_DETECTED'",
            Integer.class
        );
        assertThat(auditCount).isEqualTo(1);
    }
}
