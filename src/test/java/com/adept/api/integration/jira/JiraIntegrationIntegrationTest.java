package com.adept.api.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import java.util.regex.Pattern;

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
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.crypto.IntegrationEncryptionService;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;
import com.adept.api.integration.github.GithubApiClient;
import com.adept.api.integration.github.GithubAppTokenService;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
    "app.jira.enabled=true",
    "app.github.enabled=true"
})
class JiraIntegrationIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IntegrationEncryptionService encryptionService;

    @Autowired
    private TokenHasher tokenHasher;

    @MockitoBean
    private JiraOAuthClient jiraOAuthClient;

    @MockitoBean
    private JiraApiClient jiraApiClient;

    @MockitoBean
    private GithubApiClient githubApiClient;

    @MockitoBean
    private GithubAppTokenService githubAppTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Complete Jira connection lifecycle: connect-url, callback, project sync, tracking, mapping to repository, and disconnect")
    void jiraConnectionLifecycle() throws Exception {
        // Setup manager user and workspace
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail("jira-manager"),
                VALID_PASSWORD,
                "Jira Manager",
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

        // Insert a test repository to map Jira projects to
        UUID repositoryId = insertTestRepository(signup.workspace().id(), managerMembershipId);

        // 1. Request Connect URL
        when(jiraOAuthClient.buildAuthorizationUrl(anyString(), anyString()))
            .thenAnswer(inv -> "https://auth.atlassian.com/authorize?state=" + inv.getArgument(0));

        CsrfPair csrf1 = fetchCsrf(mockMvc);
        MvcResult connectResult = mockMvc.perform(post("/api/v1/integrations/jira/connect-url")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf1.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf1.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.url").isNotEmpty())
            .andExpect(jsonPath("$.state").isNotEmpty())
            .andReturn();

        String rawState = body(connectResult).path("state").asText();

        // 2. Handle OAuth Callback
        String authCode = "atlassian-auth-code-123";
        when(jiraOAuthClient.exchangeCode(anyString(), anyString())).thenReturn(
            new JiraOAuthClient.JiraTokenResponse(
                "mock-access-token",
                "mock-refresh-token",
                3600,
                new String[]{"read:jira-work", "manage:jira-webhook", "offline_access"}
            )
        );
        when(jiraOAuthClient.getAccessibleResources("mock-access-token")).thenReturn(
            List.of(
                new JiraOAuthClient.JiraAccessibleResource(
                    "cloud-123",
                    "https://acme.atlassian.net",
                    "Acme Jira",
                    "https://avatar.com/jira.png"
                )
            )
        );
        when(jiraApiClient.listProjects("cloud-123", "mock-access-token")).thenReturn(
            List.of(
                new JiraApiClient.JiraProjectDetails("10001", "ACME", "Acme Core", "software"),
                new JiraApiClient.JiraProjectDetails("10002", "OPS", "Platform Ops", "software")
            )
        );
        when(jiraApiClient.registerWebhook(eq("cloud-123"), eq("mock-access-token"), anyString()))
            .thenReturn(1000L);

        mockMvc.perform(get("/api/v1/integrations/jira/callback")
                .param("code", authCode)
                .param("state", rawState))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", FRONTEND_ORIGIN + "/dashboard/integrations?jira=connected"));

        // Verify tokens were encrypted in database
        Map<String, Object> integrationRow = jdbc.queryForMap(
            """
            SELECT access_token_enc, refresh_token_enc, encryption_key_version,
                   webhook_id, webhook_token_hash, webhook_expires_at
            FROM jira_integrations
            WHERE cloud_id = 'cloud-123'
            """
        );
        String encAccess = (String) integrationRow.get("access_token_enc");
        int keyVersion = ((Number) integrationRow.get("encryption_key_version")).intValue();
        assertThat(encAccess).isNotEqualTo("mock-access-token");
        assertThat(encryptionService.decrypt(encAccess, keyVersion)).isEqualTo("mock-access-token");
        assertThat(integrationRow.get("webhook_id")).isEqualTo(1000L);
        assertThat(integrationRow.get("webhook_token_hash").toString()).matches("^[0-9a-f]{64}$");
        assertThat(integrationRow.get("webhook_expires_at")).isNotNull();

        var callbackCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jiraApiClient).registerWebhook(
            eq("cloud-123"),
            eq("mock-access-token"),
            callbackCaptor.capture()
        );
        String callbackUrl = callbackCaptor.getValue();
        assertThat(callbackUrl).startsWith("http://localhost:8080/api/v1/webhooks/jira/");
        String rawWebhookToken = callbackUrl.substring(callbackUrl.indexOf("?token=") + 7);
        assertThat(rawWebhookToken).matches(Pattern.compile("^[A-Za-z0-9_-]{43}$"));
        assertThat(integrationRow.get("webhook_token_hash"))
            .isEqualTo(tokenHasher.hashJiraWebhookToken(rawWebhookToken));
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*)
            FROM processing_jobs
            WHERE job_type = 'RENEW_JIRA_WEBHOOK'
              AND payload ->> 'jiraIntegrationId' = (
                  SELECT id::text FROM jira_integrations WHERE cloud_id = 'cloud-123'
              )
            """,
            Integer.class
        )).isOne();

        // 3. Verify Integration status via API
        MvcResult integrationResult = mockMvc.perform(get("/api/v1/integrations/jira")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cloudId").value("cloud-123"))
            .andExpect(jsonPath("$.displayName").value("Acme Jira"))
            .andExpect(jsonPath("$.projectCount").value(2))
            .andReturn();

        UUID integrationId = UUID.fromString(body(integrationResult).path("id").asText());

        CsrfPair syncCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/integrations/jira/" + integrationId + "/sync")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", syncCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", syncCsrf.token())))
            .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*)
            FROM processing_jobs
            WHERE job_type = 'SYNC_JIRA_PROJECTS'
              AND payload ->> 'jiraIntegrationId' = ?
            """,
            Integer.class,
            integrationId.toString()
        )).isOne();

        // 4. List Discovered Projects
        MvcResult projectsResult = mockMvc.perform(get("/api/v1/jira/projects")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].trackingEnabled").value(false))
            .andReturn();

        UUID projectId = UUID.fromString(body(projectsResult).path(0).path("id").asText());

        // 5. Update Project Tracking
        CsrfPair csrf2 = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/jira/projects/" + projectId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf2.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trackingEnabled": true}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trackingEnabled").value(true));

        // 6. Map Jira Project to Repository
        CsrfPair csrf3 = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/repositories/" + repositoryId + "/jira-projects")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf3.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf3.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"jiraProjectIds": ["%s"]}
                    """.formatted(projectId)))
            .andExpect(status().isNoContent());

        // Verify mapping endpoint
        mockMvc.perform(get("/api/v1/repositories/" + repositoryId + "/jira-projects")
                .header("Authorization", "Bearer " + managerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(projectId.toString()));

        // 7. Disconnect Jira Integration
        CsrfPair csrf4 = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/integrations/jira/" + integrationId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf4.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf4.token())))
            .andExpect(status().isNoContent());

        String jiraStatus = jdbc.queryForObject(
            "SELECT status FROM jira_integrations WHERE id = ?",
            String.class,
            integrationId
        );
        assertThat(jiraStatus).isEqualTo("REVOKED");
        Map<String, Object> disconnectedWebhook = jdbc.queryForMap(
            "SELECT webhook_id, webhook_token_hash, webhook_expires_at FROM jira_integrations WHERE id = ?",
            integrationId
        );
        assertThat(disconnectedWebhook.values()).containsOnlyNulls();
        verify(jiraApiClient).deleteWebhook("cloud-123", "mock-access-token", 1000L);
    }

    private UUID insertTestRepository(UUID workspaceId, UUID managerMembershipId) {
        long suffix = System.nanoTime();
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, ?, ?, 'acme-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, suffix, suffix + 1, managerMembershipId);

        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled
            ) VALUES (?, ?, ?, 'acme-test', 'backend', 'acme-test/backend', 'main', 'PRIVATE', true)
            RETURNING id
            """, UUID.class, workspaceId, integrationId, suffix + 2);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
