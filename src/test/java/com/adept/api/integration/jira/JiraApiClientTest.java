package com.adept.api.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;

class JiraApiClientTest {

    private MockRestServiceServer server;
    private JiraApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new JiraApiClient(builder);
    }

    @Test
    void registersIssueWebhookUsingTheOAuthCloudApiContract() {
        server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/webhook"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
            .andExpect(content().json("""
                {
                  "url": "https://api.example.test/api/v1/webhooks/jira/integration-id?token=secret",
                  "webhooks": [{
                    "events": [
                      "jira:issue_created",
                      "jira:issue_updated",
                      "jira:issue_deleted"
                    ],
                    "jqlFilter": ""
                  }]
                }
                """))
            .andRespond(withSuccess("""
                {"webhookRegistrationResult": [{"createdWebhookId": 1000}]}
                """, MediaType.APPLICATION_JSON));

        long webhookId = client.registerWebhook(
            "cloud-123",
            "access-token",
            "https://api.example.test/api/v1/webhooks/jira/integration-id?token=secret"
        );

        assertThat(webhookId).isEqualTo(1000L);
        server.verify();
    }

    @Test
    void rejectsARegistrationResponseWithoutACreatedWebhookId() {
        server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/webhook"))
            .andRespond(withSuccess("""
                {"webhookRegistrationResult": [{"errors": ["invalid JQL"]}]}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.registerWebhook(
            "cloud-123",
            "access-token",
            "https://api.example.test/webhook"
        ))
            .isInstanceOf(ApiException.class)
            .matches(exception -> ((ApiException) exception).code()
                == ProblemCode.INTEGRATION_PROVIDER_ERROR);
    }

    @Test
    void findsAStoredWebhookAcrossThePaginatedCatalog() {
        server.expect(requestTo(
                "https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/webhook?startAt=0&maxResults=100"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
            .andRespond(withSuccess("""
                {
                  "isLast": false,
                  "maxResults": 1,
                  "startAt": 0,
                  "values": [{"id": 999}]
                }
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                "https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/webhook?startAt=1&maxResults=100"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {
                  "isLast": true,
                  "maxResults": 1,
                  "startAt": 1,
                  "values": [{"id": 1000}]
                }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.webhookExists("cloud-123", "access-token", 1000L)).isTrue();
        server.verify();
    }

    @Test
    void reportsWhenAStoredWebhookIsAbsentFromTheCatalog() {
        server.expect(requestTo(
                "https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/webhook?startAt=0&maxResults=100"))
            .andRespond(withSuccess("""
                {"isLast": true, "maxResults": 100, "startAt": 0, "values": []}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.webhookExists("cloud-123", "access-token", 1000L)).isFalse();
        server.verify();
    }

    @Test
    void refreshesAndDeletesDynamicWebhooks() {
        server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/webhook/refresh"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
            .andExpect(content().json("{\"webhookIds\": [1000]}"))
            .andRespond(withSuccess("""
                {"expirationDate": "2026-09-19T10:00:00.000+0000"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/webhook"))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
            .andExpect(content().json("{\"webhookIds\": [1000]}"))
            .andRespond(withAccepted());

        Instant expiresAt = client.refreshWebhook("cloud-123", "access-token", 1000L);
        client.deleteWebhook("cloud-123", "access-token", 1000L);

        assertThat(expiresAt).isEqualTo(Instant.parse("2026-09-19T10:00:00Z"));
        server.verify();
    }

    @Test
    void identifiesAnExpiredWebhookDuringRefresh() {
        server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-123/rest/api/3/webhook/refresh"))
            .andRespond(withStatus(HttpStatus.GONE));

        assertThatThrownBy(() -> client.refreshWebhook("cloud-123", "access-token", 1000L))
            .isInstanceOf(JiraApiClient.JiraWebhookNotFoundException.class);
        server.verify();
    }
}
