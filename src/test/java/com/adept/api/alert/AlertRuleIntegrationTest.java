package com.adept.api.alert;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.AuthService;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertRuleIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void managerCanCreateListUpdateAndDeleteAlertRule() throws Exception {
        ManagerFixture manager = createManager("manager-alert-test");
        UUID repositoryId = insertRepository(manager.workspaceId(), manager.membershipId());

        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult createResult = mockMvc.perform(post("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositoryId": "%s",
                      "name": "High Lead Time",
                      "metricType": "CHANGE_LEAD_TIME_HOURS",
                      "comparator": "GT",
                      "thresholdValue": 24.5,
                      "evaluationWindowMinutes": 1440,
                      "cooldownMinutes": 720
                    }
                    """.formatted(repositoryId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("High Lead Time"))
            .andExpect(jsonPath("$.metricType").value("CHANGE_LEAD_TIME_HOURS"))
            .andExpect(jsonPath("$.comparator").value("GT"))
            .andExpect(jsonPath("$.thresholdValue").value(24.5))
            .andExpect(jsonPath("$.evaluationWindowMinutes").value(1440))
            .andExpect(jsonPath("$.cooldownMinutes").value(720))
            .andExpect(jsonPath("$.channel").value("EMAIL"))
            .andExpect(jsonPath("$.destination").value(manager.email().toLowerCase()))
            .andExpect(jsonPath("$.enabled").value(true))
            .andReturn();

        UUID ruleId = UUID.fromString(body(createResult).path("id").asText());

        // Verify list returns this rule
        mockMvc.perform(get("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(ruleId.toString()));

        // Verify filter by repositoryId returns this rule
        mockMvc.perform(get("/api/v1/alert-rules?repositoryId=" + repositoryId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(ruleId.toString()));

        // Update rule
        CsrfPair updateCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/alert-rules/" + ruleId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", updateCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", updateCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Very High Lead Time",
                      "thresholdValue": 48.0,
                      "enabled": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Very High Lead Time"))
            .andExpect(jsonPath("$.thresholdValue").value(48.0))
            .andExpect(jsonPath("$.enabled").value(false));

        // Delete rule
        CsrfPair deleteCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/alert-rules/" + ruleId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", deleteCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", deleteCsrf.token())))
            .andExpect(status().isNoContent());

        // Verify rule is deleted
        mockMvc.perform(get("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        // Check audit logs
        Integer auditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE entity_type = 'ALERT_RULE' AND entity_id = ?",
            Integer.class,
            ruleId
        );
        assertThat(auditCount).isEqualTo(3);
    }

    @Test
    void leadCanManageAlertRulesOnlyForAssignedRepositories() throws Exception {
        ManagerFixture manager = createManager("lead-scope-manager");
        UUID assignedRepoId = insertRepository(manager.workspaceId(), manager.membershipId());
        UUID unassignedRepoId = insertRepository(manager.workspaceId(), manager.membershipId());

        LeadFixture lead = createLead(manager.workspaceId(), "assigned-lead");
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """, manager.workspaceId(), assignedRepoId, lead.membershipId(), manager.membershipId());

        // 1. Lead tries to create rule for unassigned repo -> 404 (REPOSITORY_NOT_FOUND)
        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositoryId": "%s",
                      "name": "Forbidden Rule",
                      "metricType": "PR_RISK_SCORE",
                      "comparator": "GTE",
                      "thresholdValue": 80
                    }
                    """.formatted(unassignedRepoId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // 2. Lead creates rule for assigned repo -> 201 Created
        CsrfPair validCsrf = fetchCsrf(mockMvc);
        MvcResult leadRuleResult = mockMvc.perform(post("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token())
                .header("X-XSRF-TOKEN", validCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", validCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositoryId": "%s",
                      "name": "PR Risk Alert",
                      "metricType": "PR_RISK_SCORE",
                      "comparator": "GTE",
                      "thresholdValue": 85.5
                    }
                    """.formatted(assignedRepoId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("PR Risk Alert"))
            .andExpect(jsonPath("$.destination").value(lead.email().toLowerCase()))
            .andReturn();

        UUID leadRuleId = UUID.fromString(body(leadRuleResult).path("id").asText());

        // 3. Lead lists rules -> sees only assigned repo rule
        mockMvc.perform(get("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(leadRuleId.toString()));

        // 4. Another lead in same workspace cannot patch or delete the rule
        LeadFixture otherLead = createLead(manager.workspaceId(), "other-lead");
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """, manager.workspaceId(), assignedRepoId, otherLead.membershipId(), manager.membershipId());

        CsrfPair otherLeadCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/alert-rules/" + leadRuleId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + otherLead.token())
                .header("X-XSRF-TOKEN", otherLeadCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", otherLeadCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Hijacked Rule"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ALERT_RULE_FORBIDDEN"));

        mockMvc.perform(delete("/api/v1/alert-rules/" + leadRuleId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + otherLead.token())
                .header("X-XSRF-TOKEN", otherLeadCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", otherLeadCsrf.token())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ALERT_RULE_FORBIDDEN"));

        // 5. Manager can update or delete this rule created by Lead
        CsrfPair managerCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/alert-rules/" + leadRuleId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", managerCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", managerCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Manager Overridden Name"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Manager Overridden Name"));

        mockMvc.perform(delete("/api/v1/alert-rules/" + leadRuleId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", managerCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", managerCsrf.token())))
            .andExpect(status().isNoContent());
    }

    @Test
    void leadCannotSetArbitraryDestinationOutsideWorkspace() throws Exception {
        ManagerFixture manager = createManager("dest-manager");
        UUID assignedRepoId = insertRepository(manager.workspaceId(), manager.membershipId());
        LeadFixture lead = createLead(manager.workspaceId(), "dest-lead");
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """, manager.workspaceId(), assignedRepoId, lead.membershipId(), manager.membershipId());

        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositoryId": "%s",
                      "name": "Spam Alert",
                      "metricType": "CHANGE_FAILURE_RATE_PERCENT",
                      "comparator": "GT",
                      "thresholdValue": 10,
                      "destination": "external-stranger@outsider.com"
                    }
                    """.formatted(assignedRepoId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Setting to manager's email (workspace member) succeeds
        mockMvc.perform(post("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositoryId": "%s",
                      "name": "Member Alert",
                      "metricType": "CHANGE_FAILURE_RATE_PERCENT",
                      "comparator": "GT",
                      "thresholdValue": 10,
                      "destination": "%s"
                    }
                    """.formatted(assignedRepoId, manager.email())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.destination").value(manager.email().toLowerCase()));
    }

    @Test
    void validationRejectsInvalidFieldsAndLeadLosesAccessWhenAssignmentRevoked() throws Exception {
        ManagerFixture manager = createManager("validation-manager");
        UUID assignedRepoId = insertRepository(manager.workspaceId(), manager.membershipId());
        LeadFixture lead = createLead(manager.workspaceId(), "validation-lead");
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """, manager.workspaceId(), assignedRepoId, lead.membershipId(), manager.membershipId());

        CsrfPair csrf = fetchCsrf(mockMvc);

        // 1. Invalid evaluation window (< 1)
        mockMvc.perform(post("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositoryId": "%s",
                      "name": "Invalid Window",
                      "metricType": "DEPLOYMENT_FREQUENCY",
                      "comparator": "LT",
                      "thresholdValue": 50,
                      "evaluationWindowMinutes": 0
                    }
                    """.formatted(assignedRepoId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 2. Invalid cooldown (< 0)
        mockMvc.perform(post("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositoryId": "%s",
                      "name": "Invalid Cooldown",
                      "metricType": "DEPLOYMENT_FREQUENCY",
                      "comparator": "LT",
                      "thresholdValue": 50,
                      "cooldownMinutes": -5
                    }
                    """.formatted(assignedRepoId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 3. Create valid rule by lead
        MvcResult createResult = mockMvc.perform(post("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "repositoryId": "%s",
                      "name": "Valid Lead Rule",
                      "metricType": "DEPLOYMENT_FREQUENCY",
                      "comparator": "LT",
                      "thresholdValue": 50
                    }
                    """.formatted(assignedRepoId)))
            .andExpect(status().isCreated())
            .andReturn();

        UUID ruleId = UUID.fromString(body(createResult).path("id").asText());

        // 4. Revoke lead assignment
        jdbc.update("DELETE FROM repository_lead_assignments WHERE lead_membership_id = ?", lead.membershipId());

        // 5. Lead attempts to update rule they created -> 404 (Repository scope revoked)
        CsrfPair updateCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(patch("/api/v1/alert-rules/" + ruleId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token())
                .header("X-XSRF-TOKEN", updateCsrf.token())
                .cookie(new Cookie("XSRF-TOKEN", updateCsrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name": "Attempt Update After Revoked"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // 6. Lead lists rules -> receives empty list
        mockMvc.perform(get("/api/v1/alert-rules")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + lead.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    private ManagerFixture createManager(String prefix) {
        String email = uniqueEmail(prefix);
        SignupResponse signup = authService.signup(
            new SignupRequest(
                email,
                VALID_PASSWORD,
                "Manager User",
                prefix + " Workspace",
                "UTC"
            ),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        UUID membershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ?",
            UUID.class,
            signup.workspace().id(),
            signup.user().id()
        );
        String token = jwtService.issue(new AuthenticatedPrincipal(
            signup.user().id(),
            membershipId,
            signup.workspace().id(),
            MembershipRole.MANAGER,
            0
        ));
        return new ManagerFixture(signup.workspace().id(), membershipId, token, email);
    }

    private LeadFixture createLead(UUID workspaceId, String prefix) {
        String email = uniqueEmail(prefix);
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status, email_verified_at,
                token_version, created_at, updated_at, version
            ) VALUES (?, ?, 'unused-test-hash', 'Lead User', 'ACTIVE', now(), 0, now(), now(), 0)
            """, userId, email);
        jdbc.update("""
            INSERT INTO memberships (
                id, workspace_id, user_id, role, status, joined_at, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'LEAD', 'ACTIVE', now(), now(), now(), 0)
            """, membershipId, workspaceId, userId);
        String token = jwtService.issue(new AuthenticatedPrincipal(
            userId,
            membershipId,
            workspaceId,
            MembershipRole.LEAD,
            0
        ));
        return new LeadFixture(userId, membershipId, token, email);
    }

    private UUID insertRepository(UUID workspaceId, UUID managerMembershipId) {
        UUID installationId = UUID.randomUUID();
        long suffix = Math.abs(installationId.getMostSignificantBits());
        String repositoryName = "repo-" + installationId.toString().substring(0, 8);
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, ?, ?, 'adept-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, suffix, suffix + 1, managerMembershipId);
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, archived
            ) VALUES (?, ?, ?, 'adept-test', ?, ?, 'main', 'PRIVATE', true, false)
            RETURNING id
            """, UUID.class,
            workspaceId,
            integrationId,
            suffix + 2,
            repositoryName,
            "adept-test/" + repositoryName);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record ManagerFixture(UUID workspaceId, UUID membershipId, String token, String email) {
    }

    private record LeadFixture(UUID userId, UUID membershipId, String token, String email) {
    }
}
