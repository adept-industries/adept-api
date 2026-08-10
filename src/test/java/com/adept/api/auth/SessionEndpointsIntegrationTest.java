package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

class SessionEndpointsIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void switchWorkspaceAcceptsOwnMembershipWithoutRotatingAndHidesUnrelatedTargets() throws Exception {
        String email = uniqueEmail("switch-ws");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Switch User", "Primary Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        UUID primaryWorkspaceId = signup.workspace().id();

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

        Cookie refreshCookie = loginResult.getResponse().getCookie("adept_refresh");

        CsrfPair csrf2 = fetchCsrf(mockMvc);
        MvcResult switchResult = mockMvc.perform(post("/api/v1/auth/switch-workspace/" + primaryWorkspaceId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie(), refreshCookie))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.currentMembership.workspaceId").value(primaryWorkspaceId.toString()))
            .andReturn();

        List<RefreshToken> tokens = refreshTokenRepository.findAll();
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).getRotatedAt()).isNull();
        assertThat(switchResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
            .noneMatch(headerValue -> headerValue.startsWith("XSRF-TOKEN="));

        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'WORKSPACE_SWITCHED'",
            Integer.class
        );
        assertThat(auditCount).isGreaterThanOrEqualTo(1);

        UUID randomWorkspaceId = UUID.randomUUID();
        CsrfPair unrelatedCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/switch-workspace/" + randomWorkspaceId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", unrelatedCsrf.token())
                .cookie(unrelatedCsrf.cookie(), refreshCookie))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void logoutIsIdempotentAndMalformedCookiesAreCleared() throws Exception {
        String email = uniqueEmail("logout-test");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Logout User", "Logout Workspace", "UTC"),
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

        Cookie refreshCookie = loginResult.getResponse().getCookie("adept_refresh");

        CsrfPair csrf2 = fetchCsrf(mockMvc);
        MvcResult logoutResult = mockMvc.perform(post("/api/v1/auth/logout")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie(), refreshCookie))
            .andExpect(status().isNoContent())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();

        Cookie clearedCookie = logoutResult.getResponse().getCookie("adept_refresh");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isEqualTo(0);

        List<RefreshToken> tokens = refreshTokenRepository.findAll();
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).getRevokedAt()).isNotNull();

        Integer logoutAuditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'LOGOUT'",
            Integer.class
        );
        assertThat(logoutAuditCount).isEqualTo(1);

        CsrfPair csrf3 = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf3.token())
                .cookie(csrf3.cookie()))
            .andExpect(status().isNoContent());

        Cookie malformed = new Cookie("adept_refresh", "malformed-refresh-value");

        CsrfPair refreshCsrf = fetchCsrf(mockMvc);
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", refreshCsrf.token())
                .cookie(refreshCsrf.cookie(), malformed)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"))
            .andReturn();
        assertExactRefreshDeletion(refreshResult);

        CsrfPair logoutCsrf = fetchCsrf(mockMvc);
        MvcResult malformedLogoutResult = mockMvc.perform(post("/api/v1/auth/logout")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", logoutCsrf.token())
                .cookie(logoutCsrf.cookie(), malformed))
            .andExpect(status().isNoContent())
            .andReturn();
        assertExactRefreshDeletion(malformedLogoutResult);
    }

    @Test
    void passwordResetRevokesAllRefreshTokensAndInvalidatesExistingAccessToken() throws Exception {
        String email = uniqueEmail("reset-hook");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Reset Hook User", "Reset Hook Workspace", "UTC"),
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

        String body = loginResult.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asText();

        CsrfPair csrf2 = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "email": "%s"
                    }
                    """.formatted(email)))
            .andExpect(status().isAccepted());

        String rawActionToken = awaitToken(email, "Reset your Adept password");

        CsrfPair csrf3 = fetchCsrf(mockMvc);
        MvcResult resetResult = mockMvc.perform(post("/api/v1/auth/reset-password")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf3.token())
                .cookie(csrf3.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "token": "%s",
                        "newPassword": "%s"
                    }
                    """.formatted(rawActionToken, "BrandNewPassword123!")))
            .andExpect(status().isNoContent())
            .andReturn();

        Cookie clearedCookie = resetResult.getResponse().getCookie("adept_refresh");
        assertThat(clearedCookie).isNotNull();
        assertThat(clearedCookie.getMaxAge()).isEqualTo(0);

        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    private static void assertExactRefreshDeletion(MvcResult result) {
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
            .anySatisfy(headerValue -> assertThat(headerValue)
                .startsWith("adept_refresh=")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=0")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Domain="));
    }
}
