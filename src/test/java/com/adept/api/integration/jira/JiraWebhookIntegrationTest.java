package com.adept.api.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.adept.api.auth.AuthService;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.crypto.SecureTokenGenerator;
import com.adept.api.crypto.TokenHasher;

@TestPropertySource(properties = {
    "app.jira.enabled=true",
    "app.github.enabled=false"
})
class JiraWebhookIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired private AuthService authService;
    @Autowired private MockMvc mockMvc;
    @Autowired private SecureTokenGenerator tokenGenerator;
    @Autowired private TokenHasher tokenHasher;

    @Test
    @DisplayName("Jira-only configuration authenticates, deduplicates, and queues dynamic webhooks")
    void jiraOnlyWebhookLifecycle() throws Exception {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail("jira-webhook"),
                VALID_PASSWORD,
                "Jira Webhook Manager",
                "Jira Webhook Workspace",
                "UTC"
            ),
            requestContext()
        );
        String rawWebhookToken = tokenGenerator.generate();
        UUID integrationId = insertIntegration(
            signup.workspace().id(),
            tokenHasher.hashJiraWebhookToken(rawWebhookToken)
        );
        insertJiraProject(signup.workspace().id(), integrationId, "10000", true);
        String callbackPath = "/api/v1/webhooks/jira/" + integrationId;
        String payload = """
            {
              "timestamp": 1787220000000,
              "webhookEvent": "jira:issue_updated",
              "issue": {
                "id": "10001",
                "key": "ADEPT-1",
                "fields": {"project": {"id": "10000", "key": "ADEPT"}}
              }
            }
            """;

        mockMvc.perform(post(callbackPath)
                .param("token", rawWebhookToken)
                .header("Authorization", "Bearer provider-token-must-not-be-stored")
                .header("X-Atlassian-Webhook-Identifier", "delivery-42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isAccepted());

        // Atlassian retries use the same identifier and must not create another event or job.
        mockMvc.perform(post(callbackPath)
                .param("token", rawWebhookToken)
                .header("X-Atlassian-Webhook-Identifier", "delivery-42")
                .header("X-Atlassian-Webhook-Retry", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM raw_webhook_events WHERE source = 'JIRA'",
            Integer.class
        )).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM processing_jobs WHERE job_type = 'PROCESS_JIRA_EVENT'",
            Integer.class
        )).isOne();
        assertThat(jdbc.queryForObject(
            """
            SELECT headers ? 'authorization'
            FROM raw_webhook_events
            WHERE source = 'JIRA'
            """,
            Boolean.class
        )).isFalse();
    }

    @Test
    @DisplayName("Jira webhook rejects a wrong token before parsing or persisting the payload")
    void rejectsWrongTokenBeforePayloadParsing() throws Exception {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail("jira-invalid-webhook"),
                VALID_PASSWORD,
                "Jira Webhook Manager",
                "Invalid Jira Webhook Workspace",
                "UTC"
            ),
            requestContext()
        );
        UUID integrationId = insertIntegration(
            signup.workspace().id(),
            tokenHasher.hashJiraWebhookToken(tokenGenerator.generate())
        );
        String wrongButWellFormedToken = tokenGenerator.generate();

        mockMvc.perform(post("/api/v1/webhooks/jira/" + integrationId)
                .param("token", wrongButWellFormedToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("not-json"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));

        mockMvc.perform(post("/api/v1/webhooks/jira/" + integrationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("not-json"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM raw_webhook_events WHERE source = 'JIRA'",
            Integer.class
        )).isZero();
    }

    @Test
    @DisplayName("Jira webhook does not expose whether an integration ID exists")
    void unknownIntegrationReturnsSameAuthenticationFailure() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/jira/" + UUID.randomUUID())
                .param("token", tokenGenerator.generate())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("Jira webhook does not retain issues from tracking-disabled projects")
    void ignoresUntrackedProjectWithoutRetainingPayload() throws Exception {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail("jira-untracked-webhook"),
                VALID_PASSWORD,
                "Jira Webhook Manager",
                "Untracked Jira Workspace",
                "UTC"
            ),
            requestContext()
        );
        String rawWebhookToken = tokenGenerator.generate();
        UUID integrationId = insertIntegration(
            signup.workspace().id(),
            tokenHasher.hashJiraWebhookToken(rawWebhookToken)
        );
        insertJiraProject(signup.workspace().id(), integrationId, "10000", false);

        mockMvc.perform(post("/api/v1/webhooks/jira/" + integrationId)
                .param("token", rawWebhookToken)
                .header("X-Atlassian-Webhook-Identifier", "delivery-untracked")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "webhookEvent": "jira:issue_updated",
                      "issue": {
                        "id": "sensitive-untracked-issue",
                        "fields": {"project": {"id": "10000"}}
                      }
                    }
                    """))
            .andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM raw_webhook_events WHERE source = 'JIRA'",
            Integer.class
        )).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM processing_jobs WHERE job_type = 'PROCESS_JIRA_EVENT'",
            Integer.class
        )).isZero();
    }

    private UUID insertIntegration(UUID workspaceId, String webhookTokenHash) {
        return jdbc.queryForObject("""
            INSERT INTO jira_integrations (
                workspace_id, cloud_id, site_url, display_name,
                access_token_enc, refresh_token_enc, encryption_key_version,
                access_token_expires_at, scopes, status, webhook_token_hash
            ) VALUES (?, ?, 'https://adept-test.atlassian.net', 'Adept Jira',
                      'encrypted-access', 'encrypted-refresh', 1,
                      now() + interval '1 hour',
                      ARRAY['read:jira-work', 'manage:jira-webhook'], 'ACTIVE', ?)
            RETURNING id
            """,
            UUID.class,
            workspaceId,
            "cloud-" + UUID.randomUUID(),
            webhookTokenHash
        );
    }

    private void insertJiraProject(
            UUID workspaceId,
            UUID integrationId,
            String jiraProjectId,
            boolean trackingEnabled) {
        jdbc.update("""
            INSERT INTO jira_projects (
                workspace_id, jira_integration_id, jira_project_id,
                project_key, project_name, project_type, tracking_enabled
            ) VALUES (?, ?, ?, ?, 'Adept Project', 'software', ?)
            """,
            workspaceId,
            integrationId,
            jiraProjectId,
            "ADEPT-" + UUID.randomUUID().toString().substring(0, 8),
            trackingEnabled
        );
    }
}
