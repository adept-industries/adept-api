package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

class JwtAuthenticationIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validBearerTokenAccessesMeEndpoint() throws Exception {
        String email = uniqueEmail("jwt-valid");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Valid JWT User", "JWT Workspace", "UTC"),
            requestContext()
        );

        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
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

        String body = loginResult.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.currentMembership").exists());
    }

    @Test
    void currentDatabaseStateAlwaysInvalidatesStaleBearerClaims() throws Exception {
        String email = uniqueEmail("jwt-live-state");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Live State User", "Live State Workspace", "UTC"),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        jdbc.update("UPDATE memberships SET role = 'LEAD' WHERE user_id = ?", signup.user().id());

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
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
            .andExpect(jsonPath("$.currentMembership.role").value("LEAD"))
            .andReturn();
        String accessToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
            .get("accessToken").asText();

        jdbc.update("UPDATE memberships SET role = 'MANAGER' WHERE user_id = ?", signup.user().id());
        assertSessionInvalid(accessToken);
        jdbc.update("UPDATE memberships SET role = 'LEAD' WHERE user_id = ?", signup.user().id());

        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", signup.user().id());
        assertSessionInvalid(accessToken);
        jdbc.update("UPDATE users SET token_version = token_version - 1 WHERE id = ?", signup.user().id());

        jdbc.update("UPDATE memberships SET status = 'SUSPENDED' WHERE user_id = ?", signup.user().id());
        assertSessionInvalid(accessToken);
        jdbc.update("UPDATE memberships SET status = 'ACTIVE' WHERE user_id = ?", signup.user().id());

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", signup.user().id());
        assertSessionInvalid(accessToken);
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", signup.user().id());

        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE id = ?", signup.user().id());
        assertSessionInvalid(accessToken);
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        jdbc.update("UPDATE workspaces SET status = 'DELETING' WHERE id = ?", signup.workspace().id());
        assertSessionInvalid(accessToken);
    }

    @Test
    void staleBearerTokenDoesNotBlockRefreshOrLoginEndpoint() throws Exception {
        String email = uniqueEmail("jwt-stale-unblock");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Unblock User", "Unblock Workspace", "UTC"),
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
        mockMvc.perform(post("/api/v1/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer garbage-stale-token")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie(), refreshCookie)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    @Test
    void malformedOrInvalidTokenIsRejectedOnMeEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    private void assertSessionInvalid(String accessToken) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }
}
