package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(parentRow.getAuthenticatedAt()).isNotNull();
        assertThat(childRow.getAuthenticatedAt()).isEqualTo(parentRow.getAuthenticatedAt());
        assertThat(refreshResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
            .noneMatch(headerValue -> headerValue.startsWith("XSRF-TOKEN="));
    }

    @Test
    void invalidWorkspacePreferencesResolveOnlyAgainstActiveChoices() throws Exception {
        SignupResponse singleSignup = verifiedSignup("pref-single", "Only Workspace");
        Cookie singleRefreshCookie = loginCookie(singleSignup.user().email());

        CsrfPair singleCsrf = fetchCsrf(mockMvc);
        MvcResult singleResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", singleCsrf.token())
                .cookie(singleCsrf.cookie(), singleRefreshCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"workspaceId":"%s"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(false))
            .andExpect(jsonPath("$.currentMembership.workspaceId").value(singleSignup.workspace().id().toString()))
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn();

        assertThat(singleResult.getResponse().getCookie("adept_refresh")).isNotNull();
        assertThat(refreshTokenRepository.findAll()).hasSize(2);

        SignupResponse multiSignup = verifiedSignup("pref-many", "First Workspace");
        UUID secondWorkspaceId = jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone, status)
            VALUES ('Second Workspace', ?, 'UTC', 'ACTIVE')
            RETURNING id
            """, UUID.class, "second-" + UUID.randomUUID());
        jdbc.update("""
            INSERT INTO memberships (workspace_id, user_id, role, status)
            VALUES (?, ?, 'LEAD', 'ACTIVE')
            """, secondWorkspaceId, multiSignup.user().id());
        Cookie multiRefreshCookie = loginCookie(multiSignup.user().email());

        CsrfPair multiCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", multiCsrf.token())
                .cookie(multiCsrf.cookie(), multiRefreshCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"workspaceId":"%s"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(true))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.currentMembership").doesNotExist())
            .andExpect(jsonPath("$.workspaces.length()").value(2));
    }

    @Test
    void reuseDetectionIsIdempotentAcrossDifferentRotatedRowsInOneFamily() throws Exception {
        SignupResponse signup = verifiedSignup("refresh-family-idempotent", "Family Workspace");
        Cookie parent = loginCookie(signup.user().email());
        Cookie child = rotateSuccessfully(parent).getResponse().getCookie("adept_refresh");
        assertThat(child).isNotNull();
        rotateSuccessfully(child);

        replayExpectingReuse(parent);
        replayExpectingReuse(child);

        assertThat(jdbc.queryForObject(
            "SELECT token_version FROM users WHERE id = ?",
            Integer.class,
            signup.user().id()
        )).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'REFRESH_REUSE_DETECTED'",
            Integer.class
        )).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM refresh_tokens WHERE family_id = "
                + "(SELECT family_id FROM refresh_tokens LIMIT 1) AND revoked_at IS NULL",
            Integer.class
        )).isZero();
    }

    @Test
    void expiredAndRevokedRefreshCredentialsFailSafelyAndClearCookie() throws Exception {
        SignupResponse expiredSignup = verifiedSignup("refresh-expired", "Expired Workspace");
        Cookie expiredCookie = loginCookie(expiredSignup.user().email());

        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 hour' WHERE user_id = ?",
            expiredSignup.user().id());

        CsrfPair expiredCsrf = fetchCsrf(mockMvc);
        MvcResult expiredResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", expiredCsrf.token())
                .cookie(expiredCsrf.cookie(), expiredCookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"))
            .andReturn();

        assertThat(expiredResult.getResponse().getCookie("adept_refresh").getMaxAge()).isZero();

        SignupResponse revokedSignup = verifiedSignup("refresh-revoked", "Revoked Workspace");
        Cookie revokedCookie = loginCookie(revokedSignup.user().email());

        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ?", revokedSignup.user().id());

        CsrfPair revokedCsrf = fetchCsrf(mockMvc);
        MvcResult revokedResult = mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", revokedCsrf.token())
                .cookie(revokedCsrf.cookie(), revokedCookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"))
            .andReturn();

        assertThat(revokedResult.getResponse().getCookie("adept_refresh").getMaxAge()).isZero();
    }

    private SignupResponse verifiedSignup(String emailPrefix, String workspaceName) {
        String email = uniqueEmail(emailPrefix);
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Refresh Test User", workspaceName, "UTC"),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        return signup;
    }

    private Cookie loginCookie(String email) throws Exception {
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
            .andReturn();
        Cookie cookie = result.getResponse().getCookie("adept_refresh");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private MvcResult rotateSuccessfully(Cookie cookie) throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie(), cookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn();
    }

    private void replayExpectingReuse(Cookie cookie) throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie(), cookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REFRESH_REUSE_DETECTED"));
    }
}
