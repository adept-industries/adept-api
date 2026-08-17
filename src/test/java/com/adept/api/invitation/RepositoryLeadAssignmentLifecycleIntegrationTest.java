package com.adept.api.invitation;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.AuthService;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.integration.github.GithubApiClient;
import com.adept.api.integration.github.GithubAppTokenService;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.github.enabled=true")
class RepositoryLeadAssignmentLifecycleIntegrationTest extends PartCIntegrationTestSupport {

    @MockitoBean
    private GithubApiClient githubApiClient;

    @MockitoBean
    private GithubAppTokenService githubAppTokenService;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void leadAssignmentLifecycleSupportsCoLeadsIdempotencyUnassignmentAndProvenance() throws Exception {
        // 1. Setup Workspace and Manager
        SignupResponse managerSignup = authService.signup(
            new SignupRequest(
                uniqueEmail("assignment-mgr"),
                VALID_PASSWORD,
                "Assignment Manager",
                "Platform Eng",
                "UTC"
            ),
            requestContext()
        );
        UUID managerUserId = managerSignup.user().id();
        UUID workspaceId = managerSignup.workspace().id();
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", managerUserId);

        UUID managerMembershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ?",
            UUID.class,
            workspaceId,
            managerUserId
        );
        String managerToken = jwtService.issue(new AuthenticatedPrincipal(
            managerUserId,
            managerMembershipId,
            workspaceId,
            MembershipRole.MANAGER,
            0
        ));

        UUID repoId = insertRepository(workspaceId, managerMembershipId);

        // 2. Assign existing active LEAD member -> exclusive target type: lead_membership_id set, invitation_id null
        String lead1Email = uniqueEmail("active-lead-1");
        UUID lead1MembershipId = insertLead(workspaceId, lead1Email);

        CsrfPair csrf1 = fetchCsrf(mockMvc);
        MvcResult assignLead1Result = mockMvc.perform(post("/api/v1/repositories/" + repoId + "/lead-assignments")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf1.token())
                .cookie(csrf1.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(lead1Email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.repositoryId").value(repoId.toString()))
            .andExpect(jsonPath("$.email").value(lead1Email))
            .andExpect(jsonPath("$.role").value("LEAD"))
            .andExpect(jsonPath("$.invitationId").doesNotExist())
            .andReturn();

        UUID assignment1Id = UUID.fromString(body(assignLead1Result).path("assignmentId").asText());

        // Verify DB constraint & audit log
        var lead1AssignmentRow = jdbc.queryForMap(
            "SELECT * FROM repository_lead_assignments WHERE id = ?",
            assignment1Id
        );
        assertThat(lead1AssignmentRow.get("lead_membership_id")).isEqualTo(lead1MembershipId);
        assertThat(lead1AssignmentRow.get("invitation_id")).isNull();
        assertThat(lead1AssignmentRow.get("assigned_by_membership_id")).isEqualTo(managerMembershipId);

        Integer assignAuditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'REPOSITORY_LEAD_ASSIGNED' AND entity_id = ?",
            Integer.class,
            assignment1Id
        );
        assertThat(assignAuditCount).isGreaterThanOrEqualTo(1);

        // 3. Assign unknown email -> exclusive target type: invitation_id set, lead_membership_id null
        String pendingEmail = uniqueEmail("pending-lead-invite");
        CsrfPair csrf2 = fetchCsrf(mockMvc);
        MvcResult assignPendingResult = mockMvc.perform(post("/api/v1/repositories/" + repoId + "/lead-assignments")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(csrf2.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(pendingEmail)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.repositoryId").value(repoId.toString()))
            .andExpect(jsonPath("$.email").value(pendingEmail))
            .andExpect(jsonPath("$.role").value("LEAD"))
            .andExpect(jsonPath("$.invitationId").isNotEmpty())
            .andReturn();

        UUID pendingAssignmentId = UUID.fromString(body(assignPendingResult).path("assignmentId").asText());
        var pendingAssignmentRow = jdbc.queryForMap(
            "SELECT * FROM repository_lead_assignments WHERE id = ?",
            pendingAssignmentId
        );
        assertThat(pendingAssignmentRow.get("lead_membership_id")).isNull();
        assertThat(pendingAssignmentRow.get("invitation_id")).isNotNull();

        // 4. Distinct co-Leads on the same repository
        String lead2Email = uniqueEmail("active-lead-2");
        UUID lead2MembershipId = insertLead(workspaceId, lead2Email);

        CsrfPair csrf3 = fetchCsrf(mockMvc);
        MvcResult assignLead2Result = mockMvc.perform(post("/api/v1/repositories/" + repoId + "/lead-assignments")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf3.token())
                .cookie(csrf3.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(lead2Email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(lead2Email))
            .andReturn();

        UUID assignment2Id = UUID.fromString(body(assignLead2Result).path("assignmentId").asText());
        assertThat(assignment2Id).isNotEqualTo(assignment1Id);

        Integer totalAssignments = jdbc.queryForObject(
            "SELECT count(*) FROM repository_lead_assignments WHERE repository_id = ?",
            Integer.class,
            repoId
        );
        assertThat(totalAssignments).isEqualTo(3);

        // 5. Idempotent duplicate-pair addition returns existing assignment without duplicating
        CsrfPair csrf4 = fetchCsrf(mockMvc);
        MvcResult reassignLead1Result = mockMvc.perform(post("/api/v1/repositories/" + repoId + "/lead-assignments")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf4.token())
                .cookie(csrf4.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(lead1Email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignmentId").value(assignment1Id.toString()))
            .andReturn();

        CsrfPair csrf5 = fetchCsrf(mockMvc);
        MvcResult reassignPendingResult = mockMvc.perform(post("/api/v1/repositories/" + repoId + "/lead-assignments")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf5.token())
                .cookie(csrf5.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(pendingEmail)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignmentId").value(pendingAssignmentId.toString()))
            .andReturn();

        Integer totalAssignmentsAfterReassign = jdbc.queryForObject(
            "SELECT count(*) FROM repository_lead_assignments WHERE repository_id = ?",
            Integer.class,
            repoId
        );
        assertThat(totalAssignmentsAfterReassign).isEqualTo(3);

        // 6. Cannot assign a Manager as Lead
        String manager2Email = uniqueEmail("another-manager");
        insertManager(workspaceId, manager2Email);
        CsrfPair csrf6 = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/repositories/" + repoId + "/lead-assignments")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf6.token())
                .cookie(csrf6.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(manager2Email)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WORKSPACE_CONFLICT"));

        // 7. Target-specific unassignment (DELETE /repositories/{repoId}/lead-assignments/{assignment1Id})
        CsrfPair csrf7 = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/repositories/" + repoId + "/lead-assignments/" + assignment1Id)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf7.token())
                .cookie(csrf7.cookie()))
            .andExpect(status().isNoContent());

        // Lead 1 assignment deleted, but Lead 2 assignment still preserved
        Integer assignment1Count = jdbc.queryForObject(
            "SELECT count(*) FROM repository_lead_assignments WHERE id = ?",
            Integer.class,
            assignment1Id
        );
        assertThat(assignment1Count).isZero();

        Integer assignment2Count = jdbc.queryForObject(
            "SELECT count(*) FROM repository_lead_assignments WHERE id = ?",
            Integer.class,
            assignment2Id
        );
        assertThat(assignment2Count).isEqualTo(1);

        // Unassignment audit log recorded
        Integer unassignAuditCount = jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'REPOSITORY_LEAD_UNASSIGNED' AND entity_id = ?",
            Integer.class,
            assignment1Id
        );
        assertThat(unassignAuditCount).isGreaterThanOrEqualTo(1);

        // 8. Service-layer validation: Cannot remove Lead 2 while active assignments remain
        CsrfPair csrf8 = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/workspaces/current/members/" + lead2MembershipId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerToken)
                .header("X-XSRF-TOKEN", csrf8.token())
                .cookie(csrf8.cookie()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WORKSPACE_CONFLICT"));

        // 9. Preserved provenance: If manager membership leaves/deleted, assignment is preserved with NULL assigned_by
        jdbc.update("DELETE FROM memberships WHERE id = ?", managerMembershipId);
        var remainingAssignment = jdbc.queryForMap(
            "SELECT * FROM repository_lead_assignments WHERE id = ?",
            assignment2Id
        );
        assertThat(remainingAssignment.get("lead_membership_id")).isEqualTo(lead2MembershipId);
        assertThat(remainingAssignment.get("assigned_by_membership_id")).isNull();
    }

    private UUID insertRepository(UUID workspaceId, UUID managerMembershipId) {
        UUID installationId = UUID.randomUUID();
        long suffix = Math.abs(installationId.getMostSignificantBits());
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, ?, ?, 'adept-test-org', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, suffix, suffix + 1, managerMembershipId);
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled
            ) VALUES (?, ?, ?, 'adept-test-org', 'service-api', 'adept-test-org/service-api', 'main', 'PRIVATE', true)
            RETURNING id
            """, UUID.class, workspaceId, integrationId, suffix + 2);
    }

    private UUID insertLead(UUID workspaceId, String email) {
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
        return membershipId;
    }

    private UUID insertManager(UUID workspaceId, String email) {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status, email_verified_at,
                token_version, created_at, updated_at, version
            ) VALUES (?, ?, 'unused-test-hash', 'Manager User', 'ACTIVE', now(), 0, now(), now(), 0)
            """, userId, email);
        jdbc.update("""
            INSERT INTO memberships (
                id, workspace_id, user_id, role, status, joined_at, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'MANAGER', 'ACTIVE', now(), now(), now(), 0)
            """, membershipId, workspaceId, userId);
        return membershipId;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
