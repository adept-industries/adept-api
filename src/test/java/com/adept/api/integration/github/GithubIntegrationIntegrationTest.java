package com.adept.api.integration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.AuthService;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.GithubAccountType;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.RepositorySelection;
import com.adept.api.common.domain.RepositoryVisibility;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.github.enabled=true")
class GithubIntegrationIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GithubApiClient githubApiClient;

    @MockitoBean
    private GithubAppTokenService githubAppTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Complete GitHub connection lifecycle: connect-url, callback, sync, update tracking, settings, lead candidates, and disconnect")
    void githubConnectionLifecycle() throws Exception {
        // Setup manager user and workspace
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail("gh-manager"),
                VALID_PASSWORD,
                "GitHub Manager",
                "Acme Org",
                "UTC"
            ),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        UUID managerMembershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ?",
            UUID.class,
            signup.workspace().id(),
            signup.user().id()
        );
        String managerToken = jwtService.issue(new AuthenticatedPrincipal(
            signup.user().id(),
            managerMembershipId,
            signup.workspace().id(),
            MembershipRole.MANAGER,
            0
        ));

        // 1. Request Connect URL
        CsrfPair csrf1 = fetchCsrf(mockMvc);
        MvcResult connectUrlResult = mockMvc.perform(post("/api/v1/integrations/github/connect-url")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf1.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf1.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.url").isNotEmpty())
            .andExpect(jsonPath("$.state").isNotEmpty())
            .andReturn();

        JsonNode connectJson = body(connectUrlResult);
        String rawState = connectJson.path("state").asText();

        // Mock GitHub API responses for callback
        long installationId = 987654321L;
        when(githubApiClient.getInstallation(installationId)).thenReturn(
            new GithubApiClient.GithubInstallationDetails(
                installationId,
                111222L,
                "acme-corp",
                GithubAccountType.ORGANIZATION,
                RepositorySelection.ALL,
                Map.of("issues", "read", "contents", "read")
            )
        );
        when(githubApiClient.listInstallationRepositories(installationId)).thenReturn(
            List.of(
                new GithubApiClient.GithubRepoDetails(
                    555111L,
                    "R_node1",
                    "acme-corp",
                    "backend-service",
                    "acme-corp/backend-service",
                    "main",
                    RepositoryVisibility.PRIVATE,
                    false
                ),
                new GithubApiClient.GithubRepoDetails(
                    555222L,
                    "R_node2",
                    "acme-corp",
                    "web-client",
                    "acme-corp/web-client",
                    "main",
                    RepositoryVisibility.PUBLIC,
                    false
                )
            )
        );

        // 2. Handle GitHub OAuth Callback
        mockMvc.perform(get("/api/v1/integrations/github/callback")
                .param("installation_id", String.valueOf(installationId))
                .param("state", rawState))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", FRONTEND_ORIGIN + "/dashboard/integrations?github=connected"));

        // Verify state consumption (cannot be reused)
        mockMvc.perform(get("/api/v1/integrations/github/callback")
                .param("installation_id", String.valueOf(installationId))
                .param("state", rawState))
            .andExpect(status().isBadRequest());

        // 3. Verify Integration status via API
        MvcResult integrationResult = mockMvc.perform(get("/api/v1/integrations/github")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountLogin").value("acme-corp"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.repositoryCount").value(2))
            .andReturn();

        UUID integrationId = UUID.fromString(body(integrationResult).path("id").asText());

        // 4. List Repositories (default tracking should be false)
        MvcResult reposResult = mockMvc.perform(get("/api/v1/repositories")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].trackingEnabled").value(false))
            .andReturn();

        UUID repoId = UUID.fromString(body(reposResult).path(0).path("id").asText());

        // 5. Update Repository tracking and settings
        CsrfPair csrf2 = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/repositories/" + repoId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf2.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "trackingEnabled": true,
                        "settings": {
                            "deploymentSignal": "WORKFLOW_RUN",
                            "productionEnvironmentPatterns": ["production", "release"],
                            "deploymentWorkflowNamePatterns": ["deploy-prod"],
                            "defaultMetricGranularity": "WEEK",
                            "backfillDays": 60
                        }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trackingEnabled").value(true))
            .andExpect(jsonPath("$.settings.backfillDays").value(60));

        // Verify BACKFILL_REPOSITORY job was queued
        Integer jobCount = jdbc.queryForObject(
            "SELECT count(*) FROM processing_jobs WHERE job_type = 'BACKFILL_REPOSITORY' AND repository_id = ?",
            Integer.class,
            repoId
        );
        assertThat(jobCount).isEqualTo(1);

        // 6. Lead Candidates Lookup
        when(githubApiClient.listLeadCandidates(anyLong(), anyString(), anyString())).thenReturn(
            List.of(
                new GithubApiClient.GithubLeadCandidate(
                    "101",
                    "alice",
                    "https://avatars.githubusercontent.com/u/101",
                    "ADMIN",
                    null
                )
            )
        );

        mockMvc.perform(get("/api/v1/repositories/" + repoId + "/lead-candidates")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].login").value("alice"))
            .andExpect(jsonPath("$[0].permission").value("ADMIN"));

        // 7. Disconnect Integration
        CsrfPair csrf3 = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/integrations/github/" + integrationId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf3.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf3.token())))
            .andExpect(status().isNoContent());

        // Verify integration is REVOKED and repository tracking is set to false
        String status = jdbc.queryForObject(
            "SELECT status FROM github_integrations WHERE id = ?",
            String.class,
            integrationId
        );
        assertThat(status).isEqualTo("REVOKED");

        Boolean tracking = jdbc.queryForObject(
            "SELECT tracking_enabled FROM repositories WHERE id = ?",
            Boolean.class,
            repoId
        );
        assertThat(tracking).isFalse();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
