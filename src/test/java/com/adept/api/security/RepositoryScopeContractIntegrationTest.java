package com.adept.api.security;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.github.enabled=true")
class RepositoryScopeContractIntegrationTest extends PartCIntegrationTestSupport {

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
    void repositoryScopeContractDeniesGuessedCrossWorkspaceAndUnassignedRepositories() throws Exception {
        // 1. Setup Workspace A and Manager A
        SignupResponse signupA = authService.signup(
            new SignupRequest(
                uniqueEmail("scope-mgr-a"),
                VALID_PASSWORD,
                "Manager Alpha",
                "Alpha Org",
                "UTC"
            ),
            requestContext()
        );
        UUID workspaceAId = signupA.workspace().id();
        UUID managerAUserId = signupA.user().id();
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", managerAUserId);
        UUID managerAMembershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ?",
            UUID.class,
            workspaceAId,
            managerAUserId
        );
        String managerAToken = jwtService.issue(new AuthenticatedPrincipal(
            managerAUserId,
            managerAMembershipId,
            workspaceAId,
            MembershipRole.MANAGER,
            0
        ));

        // 2. Setup Workspace B and Manager B
        SignupResponse signupB = authService.signup(
            new SignupRequest(
                uniqueEmail("scope-mgr-b"),
                VALID_PASSWORD,
                "Manager Beta",
                "Beta Org",
                "UTC"
            ),
            requestContext()
        );
        UUID workspaceBId = signupB.workspace().id();
        UUID managerBUserId = signupB.user().id();
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", managerBUserId);
        UUID managerBMembershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ?",
            UUID.class,
            workspaceBId,
            managerBUserId
        );
        String managerBToken = jwtService.issue(new AuthenticatedPrincipal(
            managerBUserId,
            managerBMembershipId,
            workspaceBId,
            MembershipRole.MANAGER,
            0
        ));

        // 3. Create Repositories
        UUID repoA1 = insertRepository(workspaceAId, managerAMembershipId, "repo-a1");
        UUID repoA2 = insertRepository(workspaceAId, managerAMembershipId, "repo-a2");
        UUID repoB1 = insertRepository(workspaceBId, managerBMembershipId, "repo-b1");

        // 4. Setup Lead in Workspace A, assigned ONLY to repoA1
        String leadAEmail = uniqueEmail("lead-alpha");
        UUID leadAMembershipId = insertLead(workspaceAId, leadAEmail);
        String leadAToken = tokenForRole(leadAMembershipId, workspaceAId, MembershipRole.LEAD);

        jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            """, workspaceAId, repoA1, leadAMembershipId, managerAMembershipId);

        insertDeploymentFrequencySnapshot(workspaceAId, repoA1, "deployment-a1");
        insertDeploymentFrequencySnapshot(workspaceAId, repoA2, "deployment-a2");

        mockMvc.perform(get("/api/v1/metrics/summary")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.repositoryCount").value(2))
            .andExpect(jsonPath("$.deploymentFrequency.sampleSize").value(2))
            .andExpect(jsonPath("$.calculationVersion").value("dora-v2"));

        mockMvc.perform(get("/api/v1/metrics/summary")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.repositoryCount").value(1))
            .andExpect(jsonPath("$.deploymentFrequency.sampleSize").value(1));

        mockMvc.perform(get("/api/v1/metrics/summary").param("repositoryId", repoA2.toString())
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // 5. Manager A listing: sees repoA1 and repoA2, does not see repoB1
        MvcResult managerAListResult = mockMvc.perform(get("/api/v1/repositories")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode managerAList = body(managerAListResult);
        assertThat(managerAList.size()).isEqualTo(2);

        // Manager A reading repoA1 and repoA2: 200 OK
        mockMvc.perform(get("/api/v1/repositories/" + repoA1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(repoA1.toString()));

        mockMvc.perform(get("/api/v1/repositories/" + repoA2)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(repoA2.toString()));

        // 6. Cross-Workspace Guessed IDs: Manager A guessing repoB1 from Workspace B -> 404 REPOSITORY_NOT_FOUND
        CsrfPair csrf = fetchCsrf(mockMvc);

        mockMvc.perform(get("/api/v1/repositories/" + repoB1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/repositories/" + repoB1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trackingEnabled":false}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/repositories/" + repoB1 + "/lead-candidates")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/repositories/" + repoB1 + "/lead-assignments")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(uniqueEmail("cross-invite"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/repositories/" + repoB1 + "/lead-assignments/" + UUID.randomUUID())
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // 7. Lead A Scope Tests:
        // Lead A listing: sees ONLY assigned repoA1 (repoA2 and repoB1 hidden)
        MvcResult leadAListResult = mockMvc.perform(get("/api/v1/repositories")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode leadAList = body(leadAListResult);
        assertThat(leadAList.size()).isEqualTo(1);
        assertThat(leadAList.get(0).path("id").asText()).isEqualTo(repoA1.toString());

        // Lead A reading assigned repoA1: 200 OK
        mockMvc.perform(get("/api/v1/repositories/" + repoA1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(repoA1.toString()));

        // Lead A reading unassigned repoA2 in SAME workspace: 404 REPOSITORY_NOT_FOUND
        mockMvc.perform(get("/api/v1/repositories/" + repoA2)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // Lead A reading guessed cross-workspace repoB1: 404 REPOSITORY_NOT_FOUND
        mockMvc.perform(get("/api/v1/repositories/" + repoB1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        // Lead A attempting write actions: 403 MANAGER_REQUIRED
        mockMvc.perform(patch("/api/v1/repositories/" + repoA1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"trackingEnabled":false}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MANAGER_REQUIRED"));

        mockMvc.perform(post("/api/v1/repositories/" + repoA1 + "/lead-assignments")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s"}
                    """.formatted(uniqueEmail("lead-illegal-invite"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MANAGER_REQUIRED"));

        // 8. Lifecycle states for assigned repository:
        // Disable tracking on repoA1 -> Lead A can no longer see/read it (404), Manager can still read it (200)
        jdbc.update("UPDATE repositories SET tracking_enabled = false WHERE id = ?", repoA1);

        MvcResult leadAListUntracked = mockMvc.perform(get("/api/v1/repositories")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(body(leadAListUntracked).size()).isZero();

        mockMvc.perform(get("/api/v1/repositories/" + repoA1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/repositories/" + repoA1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(repoA1.toString()));

        // Enable tracking back, but archive repoA1 -> Lead A gets 404, Manager gets 200
        jdbc.update("UPDATE repositories SET tracking_enabled = true, archived = true WHERE id = ?", repoA1);

        mockMvc.perform(get("/api/v1/repositories/" + repoA1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadAToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/repositories/" + repoA1)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + managerAToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(repoA1.toString()));
    }

    private UUID insertRepository(UUID workspaceId, UUID managerMembershipId, String repoName) {
        UUID installationId = UUID.randomUUID();
        long suffix = Math.abs(installationId.getMostSignificantBits());
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, ?, ?, 'scope-org', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, suffix, suffix + 1, managerMembershipId);
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled
            ) VALUES (?, ?, ?, 'scope-org', ?, ?, 'main', 'PRIVATE', true)
            RETURNING id
            """, UUID.class, workspaceId, integrationId, suffix + 2, repoName, "scope-org/" + repoName);
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

    private void insertDeploymentFrequencySnapshot(
            UUID workspaceId,
            UUID repositoryId,
            String observationKey) {
        Instant observedAt = Instant.now().minusSeconds(60);
        jdbc.update("""
            INSERT INTO metric_snapshots (
                workspace_id, repository_id, metric_type, granularity,
                period_start, period_end, value, unit, sample_size,
                calculation_version, dimensions, calculated_at
            ) VALUES (
                ?, ?, 'DEPLOYMENT_FREQUENCY', 'DAY',
                date_trunc('day', now()), date_trunc('day', now()) + interval '1 day',
                1, 'deployments/day', 1, 'dora-v2', CAST(? AS jsonb), now()
            )
            """,
            workspaceId,
            repositoryId,
            """
                {"observations":[{"key":"%s","at":"%s","value":1.0}]}
                """.formatted(observationKey, observedAt)
        );
    }

    private String tokenForRole(UUID membershipId, UUID workspaceId, MembershipRole role) {
        UUID userId = jdbc.queryForObject(
            "SELECT user_id FROM memberships WHERE id = ?",
            UUID.class,
            membershipId
        );
        return jwtService.issue(new AuthenticatedPrincipal(
            userId,
            membershipId,
            workspaceId,
            role,
            0
        ));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
