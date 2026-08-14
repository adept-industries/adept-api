package com.adept.api.auth;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceRecoveryIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deletingTheLastWorkspaceKeepsPasswordLoginAndWorkspaceRecoveryAvailable() throws Exception {
        String email = uniqueEmail("workspace-recovery");
        SignupResponse signup = authService.signup(
            new SignupRequest(email, VALID_PASSWORD, "Recovery User", "Workspace To Delete", "UTC"),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        MvcResult initialLogin = login(email);
        Cookie originalRefresh = initialLogin.getResponse().getCookie("adept_refresh");
        assertThat(originalRefresh).isNotNull();
        String accessToken = objectMapper.readTree(initialLogin.getResponse().getContentAsString())
            .path("accessToken")
            .asText();

        CsrfPair deletionCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/workspaces/current")
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("X-XSRF-TOKEN", deletionCsrf.token())
                .cookie(deletionCsrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"confirmationSlug":"%s"}
                    """.formatted(signup.workspace().slug())))
            .andExpect(status().isAccepted());

        CsrfPair refreshCsrf = fetchCsrf(mockMvc);
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", refreshCsrf.token())
                .cookie(refreshCsrf.cookie(), originalRefresh)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(true))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.currentMembership").doesNotExist())
            .andExpect(jsonPath("$.workspaces").isEmpty())
            .andReturn();
        assertThat(refreshed.getResponse().getCookie("adept_refresh")).isNotNull();

        MvcResult returningLogin = login(email);
        Cookie recoveryRefresh = returningLogin.getResponse().getCookie("adept_refresh");
        assertThat(recoveryRefresh).isNotNull();
        var returningBody = objectMapper.readTree(returningLogin.getResponse().getContentAsString());
        assertThat(returningBody.path("workspaceSelectionRequired").asBoolean()).isTrue();
        assertThat(returningBody.has("accessToken")).isFalse();
        assertThat(returningBody.has("currentMembership")).isFalse();
        assertThat(returningBody.path("user").path("id").asText())
            .isEqualTo(signup.user().id().toString());
        assertThat(returningBody.path("workspaces").isArray()).isTrue();
        assertThat(returningBody.path("workspaces")).isEmpty();

        CsrfPair creationCsrf = fetchCsrf(mockMvc);
        MvcResult created = mockMvc.perform(post("/api/v1/auth/workspaces")
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", creationCsrf.token())
                .cookie(creationCsrf.cookie(), recoveryRefresh)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Recovered Workspace","timezone":"Asia/Colombo"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(false))
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.user.id").value(signup.user().id().toString()))
            .andExpect(jsonPath("$.currentMembership.role").value("MANAGER"))
            .andExpect(jsonPath("$.currentMembership.workspaceName").value("Recovered Workspace"))
            .andExpect(jsonPath("$.workspaces.length()").value(1))
            .andReturn();
        assertThat(created.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
            .noneMatch(value -> value.startsWith("adept_refresh="));

        String recoveredAccessToken = objectMapper.readTree(created.getResponse().getContentAsString())
            .path("accessToken")
            .asText();
        mockMvc.perform(get("/api/v1/workspaces/current")
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + recoveredAccessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Recovered Workspace"));

        CsrfPair duplicateCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/workspaces")
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", duplicateCsrf.token())
                .cookie(duplicateCsrf.cookie(), recoveryRefresh)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Duplicate Workspace","timezone":"UTC"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WORKSPACE_CONFLICT"));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE id = ?",
            Integer.class,
            signup.user().id()
        )).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM workspaces WHERE status = 'ACTIVE'",
            Integer.class
        )).isOne();
    }

    @Test
    void recoveryEndpointRequiresAValidRefreshSession() throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/workspaces")
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Unauthorized Workspace","timezone":"UTC"}
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"))
            .andReturn();

        Cookie cleared = result.getResponse().getCookie("adept_refresh");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    private MvcResult login(String email) throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        return mockMvc.perform(post("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();
    }
}
