package com.adept.api.invitation;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = "app.github.enabled=true")
class RepositoryLeadInvitationIntegrationTest extends PartCIntegrationTestSupport {

    private static final AtomicLong GITHUB_IDS = new AtomicLong(1_000_000L);

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
    void managerCreatesPendingInvitationForExistingUserWithoutCurrentWorkspaceMembership() throws Exception {
        ManagerSession manager = createManager("pending-existing-manager");
        UUID repositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), "api");
        String inviteeEmail = uniqueEmail("pending-existing").toLowerCase(Locale.ROOT);
        UUID inviteeUserId = insertVerifiedUser(inviteeEmail, "Pending Existing User");
        long usersBefore = rowCount("users");
        long membershipsBefore = rowCount("memberships");

        MvcResult result = invite(manager.token(), repositoryId, inviteeEmail.toUpperCase(Locale.ROOT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.repositoryId").value(repositoryId.toString()))
            .andExpect(jsonPath("$.email").value(inviteeEmail))
            .andExpect(jsonPath("$.role").value("LEAD"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.assignmentId").isNotEmpty())
            .andExpect(jsonPath("$.invitationId").isNotEmpty())
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andReturn();

        JsonNode response = body(result);
        UUID invitationId = UUID.fromString(response.path("invitationId").asText());
        UUID assignmentId = UUID.fromString(response.path("assignmentId").asText());

        assertThat(rowCount("users")).isEqualTo(usersBefore);
        assertThat(rowCount("memberships")).isEqualTo(membershipsBefore);
        assertThat(rowCount(
            "memberships",
            "workspace_id = ? AND user_id = ?",
            manager.workspaceId(),
            inviteeUserId
        )).isZero();
        assertPendingInvitation(invitationId, manager.workspaceId(), inviteeEmail, manager.membershipId());
        assertInvitationAssignment(assignmentId, manager.workspaceId(), repositoryId, invitationId, manager.membershipId());
    }

    @Test
    void managerReusesPendingInvitationForSameUnknownEmailAcrossRepositoriesWithoutCreatingUser() throws Exception {
        ManagerSession manager = createManager("pending-reuse-manager");
        UUID firstRepositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), "api");
        UUID secondRepositoryId = insertRepository(manager.workspaceId(), manager.membershipId(), "worker");
        String pendingEmail = uniqueEmail("pending-new").toLowerCase(Locale.ROOT);
        long usersBefore = rowCount("users");
        long membershipsBefore = rowCount("memberships");

        JsonNode first = body(invite(manager.token(), firstRepositoryId, pendingEmail)
            .andExpect(status().isOk())
            .andReturn());
        JsonNode second = body(invite(manager.token(), secondRepositoryId, pendingEmail.toUpperCase(Locale.ROOT))
            .andExpect(status().isOk())
            .andReturn());

        UUID invitationId = UUID.fromString(first.path("invitationId").asText());
        assertThat(second.path("invitationId").asText()).isEqualTo(invitationId.toString());
        assertThat(first.path("assignmentId").asText()).isNotEqualTo(second.path("assignmentId").asText());
        assertThat(rowCount("users")).isEqualTo(usersBefore);
        assertThat(rowCount("memberships")).isEqualTo(membershipsBefore);
        assertThat(rowCount("workspace_invitations", "workspace_id = ? AND lower(email) = ?",
            manager.workspaceId(), pendingEmail)).isOne();
        assertThat(rowCount("repository_lead_assignments", "invitation_id = ?", invitationId)).isEqualTo(2);
    }

    @Test
    void leadCannotInviteAndCrossWorkspaceRepositoriesAreHidden() throws Exception {
        ManagerSession current = createManager("pending-current-manager");
        ManagerSession other = createManager("pending-other-manager");
        UUID currentRepositoryId = insertRepository(current.workspaceId(), current.membershipId(), "api");
        UUID otherRepositoryId = insertRepository(other.workspaceId(), other.membershipId(), "api");

        invite(current.token(), otherRepositoryId, uniqueEmail("pending-cross"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REPOSITORY_NOT_FOUND"));

        jdbc.update("UPDATE memberships SET role = 'LEAD' WHERE id = ?", current.membershipId());
        String leadToken = jwtService.issue(new AuthenticatedPrincipal(
            current.userId(),
            current.membershipId(),
            current.workspaceId(),
            MembershipRole.LEAD,
            0
        ));

        invite(leadToken, currentRepositoryId, uniqueEmail("pending-lead-denied"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MANAGER_REQUIRED"));
    }

    private ResultActions invite(String token, UUID repositoryId, String email) throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        return mockMvc.perform(post("/api/v1/repositories/" + repositoryId + "/lead-assignments")
            .header("Origin", FRONTEND_ORIGIN)
            .header("Authorization", "Bearer " + token)
            .header("X-XSRF-TOKEN", csrf.token())
            .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s"}
                """.formatted(email)));
    }

    private ManagerSession createManager(String prefix) {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail(prefix),
                VALID_PASSWORD,
                "Pending Invitation Manager",
                "Pending Invitation Workspace",
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
        return new ManagerSession(signup.user().id(), signup.workspace().id(), membershipId, token);
    }

    private UUID insertVerifiedUser(String email, String displayName) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status, email_verified_at,
                token_version, created_at, updated_at, version
            ) VALUES (?, ?, 'unused-test-hash', ?, 'ACTIVE', now(), 0, now(), now(), 0)
            """, userId, email, displayName);
        return userId;
    }

    private UUID insertRepository(UUID workspaceId, UUID managerMembershipId, String name) {
        long suffix = GITHUB_IDS.incrementAndGet();
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, ?, ?, 'adept-invite-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, suffix, suffix + 100_000L, managerMembershipId);
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled
            ) VALUES (?, ?, ?, 'adept-invite-test', ?, ?, 'main', 'PRIVATE', true)
            RETURNING id
            """, UUID.class, workspaceId, integrationId, suffix + 200_000L, name, "adept-invite-test/" + name);
    }

    private void assertPendingInvitation(
            UUID invitationId,
            UUID workspaceId,
            String email,
            UUID invitedByMembershipId) {
        InvitationRow row = jdbc.queryForObject("""
            SELECT workspace_id, email, role, status, invited_by_membership_id,
                   length(token_hash) AS token_hash_length, expires_at > now() AS expires_in_future
            FROM workspace_invitations
            WHERE id = ?
            """, (rs, rowNum) -> new InvitationRow(
                rs.getObject("workspace_id", UUID.class),
                rs.getString("email"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getObject("invited_by_membership_id", UUID.class),
                rs.getInt("token_hash_length"),
                rs.getBoolean("expires_in_future")
            ), invitationId);
        assertThat(row).isNotNull();
        assertThat(row.workspaceId()).isEqualTo(workspaceId);
        assertThat(row.email()).isEqualTo(email);
        assertThat(row.role()).isEqualTo("LEAD");
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.invitedByMembershipId()).isEqualTo(invitedByMembershipId);
        assertThat(row.tokenHashLength()).isEqualTo(64);
        assertThat(row.expiresInFuture()).isTrue();
    }

    private void assertInvitationAssignment(
            UUID assignmentId,
            UUID workspaceId,
            UUID repositoryId,
            UUID invitationId,
            UUID assignedByMembershipId) {
        AssignmentRow row = jdbc.queryForObject("""
            SELECT workspace_id, repository_id, invitation_id, lead_membership_id,
                   assigned_by_membership_id
            FROM repository_lead_assignments
            WHERE id = ?
            """, (rs, rowNum) -> new AssignmentRow(
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getObject("invitation_id", UUID.class),
                rs.getString("lead_membership_id"),
                rs.getObject("assigned_by_membership_id", UUID.class)
            ), assignmentId);
        assertThat(row).isNotNull();
        assertThat(row.workspaceId()).isEqualTo(workspaceId);
        assertThat(row.repositoryId()).isEqualTo(repositoryId);
        assertThat(row.invitationId()).isEqualTo(invitationId);
        assertThat(row.leadMembershipId()).isNull();
        assertThat(row.assignedByMembershipId()).isEqualTo(assignedByMembershipId);
    }

    private long rowCount(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private long rowCount(String table, String whereClause, Object... args) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + whereClause, Long.class, args);
        return count == null ? 0 : count;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record ManagerSession(
        UUID userId,
        UUID workspaceId,
        UUID membershipId,
        String token
    ) {
    }

    private record InvitationRow(
        UUID workspaceId,
        String email,
        String role,
        String status,
        UUID invitedByMembershipId,
        Integer tokenHashLength,
        Boolean expiresInFuture
    ) {
    }

    private record AssignmentRow(
        UUID workspaceId,
        UUID repositoryId,
        UUID invitationId,
        String leadMembershipId,
        UUID assignedByMembershipId
    ) {
    }
}
