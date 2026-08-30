package com.adept.api.risk;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.adept.api.auth.AuthService;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectPullRequestRiskControllerIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managerSeesProjectRiskAndLeadSeesOnlyAssignedRepository() throws Exception {
        ManagerFixture manager = createManager("risk-scope-manager");
        UUID projectId = insertProject(manager, "Risk Queue");
        UUID assignedRepositoryId = insertRepository(manager, "assigned-api");
        UUID unassignedRepositoryId = insertRepository(manager, "manager-only-ui");
        attach(projectId, assignedRepositoryId, manager.workspaceId());
        attach(projectId, unassignedRepositoryId, manager.workspaceId());

        UUID leadMembershipId = insertLead(manager.workspaceId(), "risk-lead");
        assignLead(manager, assignedRepositoryId, leadMembershipId);
        UUID stalledPullRequestId = insertPrediction(
            manager.workspaceId(),
            assignedRepositoryId,
            41,
            "Review authentication boundary",
            Instant.now().minus(72, ChronoUnit.HOURS),
            "0.200000",
            "HIGH"
        );
        insertPrediction(
            manager.workspaceId(),
            unassignedRepositoryId,
            99,
            "Update dashboard",
            Instant.now().minus(12, ChronoUnit.HOURS),
            "0.500000",
            "CRITICAL"
        );

        String managerResponse = mockMvc.perform(get(
                "/api/v1/projects/" + projectId + "/pull-request-risks")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayLabel").value("Estimated review risk"))
            .andExpect(jsonPath("$.disclaimer").value(PrRiskContract.DISCLAIMER))
            .andExpect(jsonPath("$.modelVersion").value(PrRiskContract.MODEL_VERSION))
            .andExpect(jsonPath("$.featureSchemaVersion").value(PrRiskContract.FEATURE_SCHEMA_VERSION))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.items[0].riskLevel").value("CRITICAL"))
            .andExpect(jsonPath("$.items[1].pullRequestId").value(stalledPullRequestId.toString()))
            .andExpect(jsonPath("$.items[1].stalled").value(true))
            .andExpect(jsonPath("$.items[1].topFactors[0].explanationType")
                .value("global_model_importance"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(managerResponse)
            .doesNotContain("secret source", "rawData", "diff");

        String leadToken = tokenForLead(leadMembershipId, manager.workspaceId());
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/pull-request-risks")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].repositoryId").value(assignedRepositoryId.toString()))
            .andExpect(jsonPath("$.items[0].riskLevel").value("HIGH"));

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/pull-request-risks")
                .param("stalledOnly", "true")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].pullRequestId").value(stalledPullRequestId.toString()));

        mockMvc.perform(get("/api/v1/projects/" + projectId + "/pull-request-risks")
                .param("riskLevel", "CRITICAL")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].repositoryId").value(unassignedRepositoryId.toString()));

        UUID unrelatedLeadMembershipId = insertLead(manager.workspaceId(), "unrelated-risk-lead");
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/pull-request-risks")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer "
                    + tokenForLead(unrelatedLeadMembershipId, manager.workspaceId())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void managerQueuesOneRiskOnlyBackfillPerEligibleRepository() throws Exception {
        ManagerFixture manager = createManager("risk-rebuild-manager");
        UUID projectId = insertProject(manager, "Risk Rebuild");
        UUID firstRepositoryId = insertRepository(manager, "first-service");
        UUID secondRepositoryId = insertRepository(manager, "second-service");
        attach(projectId, firstRepositoryId, manager.workspaceId());
        attach(projectId, secondRepositoryId, manager.workspaceId());

        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/pull-request-risks/rebuild")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.modelVersion").value(PrRiskContract.MODEL_VERSION))
            .andExpect(jsonPath("$.queuedRepositories").value(2))
            .andExpect(jsonPath("$.alreadyQueuedRepositories").value(0));

        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM processing_jobs
            WHERE job_type = 'BACKFILL_REPOSITORY'
              AND payload ->> 'riskOnly' = 'true'
              AND payload ->> 'modelVersion' = ?
            """, Integer.class, PrRiskContract.MODEL_VERSION)).isEqualTo(2);

        mockMvc.perform(post("/api/v1/projects/" + projectId + "/pull-request-risks/rebuild")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.queuedRepositories").value(0))
            .andExpect(jsonPath("$.alreadyQueuedRepositories").value(2));

        UUID leadMembershipId = insertLead(manager.workspaceId(), "rebuild-lead");
        assignLead(manager, firstRepositoryId, leadMembershipId);
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/pull-request-risks/rebuild")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer "
                    + tokenForLead(leadMembershipId, manager.workspaceId()))
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MANAGER_REQUIRED"));
    }

    @Test
    void paginationIsValidatedAndCrossWorkspaceProjectIdsAreHidden() throws Exception {
        ManagerFixture manager = createManager("risk-validation-manager");
        ManagerFixture otherManager = createManager("other-risk-manager");
        UUID otherProjectId = insertProject(otherManager, "Other Project");

        mockMvc.perform(get("/api/v1/projects/" + otherProjectId + "/pull-request-risks")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        UUID projectId = insertProject(manager, "Validation Project");
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/pull-request-risks")
                .param("size", "101")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ManagerFixture createManager(String prefix) {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail(prefix),
                VALID_PASSWORD,
                "Risk Manager",
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
        return new ManagerFixture(signup.workspace().id(), membershipId, token);
    }

    private UUID insertProject(ManagerFixture manager, String name) {
        return jdbc.queryForObject("""
            INSERT INTO projects (workspace_id, name, created_by_membership_id)
            VALUES (?, ?, ?)
            RETURNING id
            """, UUID.class, manager.workspaceId(), name, manager.membershipId());
    }

    private UUID insertRepository(ManagerFixture manager, String name) {
        UUID marker = UUID.randomUUID();
        long suffix = Math.abs(marker.getMostSignificantBits());
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, ?, ?, 'adept-risk-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, manager.workspaceId(), suffix, suffix + 1, manager.membershipId());
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, archived
            ) VALUES (?, ?, ?, 'adept-risk-test', ?, ?, 'main', 'PRIVATE', true, false)
            RETURNING id
            """, UUID.class,
            manager.workspaceId(),
            integrationId,
            suffix + 2,
            name,
            "adept-risk-test/" + name);
    }

    private void attach(UUID projectId, UUID repositoryId, UUID workspaceId) {
        jdbc.update("""
            INSERT INTO project_repositories (project_id, repository_id, workspace_id)
            VALUES (?, ?, ?)
            """, projectId, repositoryId, workspaceId);
    }

    private UUID insertPrediction(
            UUID workspaceId,
            UUID repositoryId,
            int number,
            String title,
            Instant openedAt,
            String score,
            String level) {
        UUID pullRequestId = jdbc.queryForObject("""
            INSERT INTO pull_requests (
                workspace_id, repository_id, github_pr_id, number, title, state, draft,
                author_login, base_ref, head_ref, head_sha, additions, deletions,
                changed_files, commit_count, opened_at, last_synced_at, raw_data
            ) VALUES (?, ?, ?, ?, ?, 'OPEN', false, 'octocat', 'main', 'feature/risk',
                repeat('a', 40), 20, 5, 3, 2, ?, now(), CAST(? AS jsonb))
            RETURNING id
            """, UUID.class,
            workspaceId,
            repositoryId,
            10_000L + number,
            number,
            title,
            Timestamp.from(openedAt),
            "{\"diff\":\"secret source\"}");
        UUID featureId = jdbc.queryForObject("""
            INSERT INTO pull_request_features (
                workspace_id, repository_id, pull_request_id, feature_schema_version,
                lines_added, lines_deleted, files_changed, commit_count, entropy, feature_payload
            ) VALUES (?, ?, ?, ?, 20, 5, 3, 2, 0.9,
                '{"ns":2,"nd":3,"fix":0,"modelInputFeatureOrder":["ns","nd","nf","entropy","la","ld","fix"]}')
            RETURNING id
            """, UUID.class,
            workspaceId,
            repositoryId,
            pullRequestId,
            PrRiskContract.FEATURE_SCHEMA_VERSION);
        jdbc.update("""
            INSERT INTO risk_predictions (
                workspace_id, repository_id, pull_request_id, feature_id, model_name,
                model_version, risk_score, risk_level, threshold_used, top_factors, predicted_at
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS numeric), ?, 0.17,
                '[{"feature":"nf","value":3,"globalImportance":0.2,"explanationType":"global_model_importance"}]',
                now())
            """,
            workspaceId,
            repositoryId,
            pullRequestId,
            featureId,
            PrRiskContract.MODEL_NAME,
            PrRiskContract.MODEL_VERSION,
            score,
            level);
        return pullRequestId;
    }

    private UUID insertLead(UUID workspaceId, String prefix) {
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status, email_verified_at,
                token_version, created_at, updated_at, version
            ) VALUES (?, ?, 'unused-test-hash', 'Lead', 'ACTIVE', now(), 0, now(), now(), 0)
            """, userId, uniqueEmail(prefix));
        jdbc.update("""
            INSERT INTO memberships (
                id, workspace_id, user_id, role, status, joined_at, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'LEAD', 'ACTIVE', now(), now(), now(), 0)
            """, membershipId, workspaceId, userId);
        return membershipId;
    }

    private void assignLead(ManagerFixture manager, UUID repositoryId, UUID leadMembershipId) {
        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """,
            manager.workspaceId(),
            repositoryId,
            leadMembershipId,
            manager.membershipId());
    }

    private String tokenForLead(UUID membershipId, UUID workspaceId) {
        UUID userId = jdbc.queryForObject(
            "SELECT user_id FROM memberships WHERE id = ?",
            UUID.class,
            membershipId
        );
        return jwtService.issue(new AuthenticatedPrincipal(
            userId,
            membershipId,
            workspaceId,
            MembershipRole.LEAD,
            0
        ));
    }

    private record ManagerFixture(UUID workspaceId, UUID membershipId, String token) {
    }
}
