package com.adept.api.invitation;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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
import com.adept.api.crypto.PasswordService;
import com.adept.api.crypto.TokenHasher;
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
class InvitationLifecycleIntegrationTest extends PartCIntegrationTestSupport {

    private static final AtomicLong GITHUB_IDS = new AtomicLong(2_000_000L);

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private TokenHasher tokenHasher;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GithubApiClient githubApiClient;

    @MockitoBean
    private GithubAppTokenService githubAppTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullInvitationLifecycleNewUserLeadOnlyRoleExpiryAndOneUseAcceptance() throws Exception {
        ManagerSession manager = createManager("lifecycle-mgr");
        UUID repo1 = insertRepository(manager.workspaceId(), manager.membershipId(), "api-core");
        UUID repo2 = insertRepository(manager.workspaceId(), manager.membershipId(), "web-app");
        String inviteeEmail = uniqueEmail("lifecycle-new").toLowerCase(Locale.ROOT);

        mailSender.reset();

        // 1. Manager invites new email for repo1
        MvcResult inviteResult1 = invite(manager.token(), repo1, inviteeEmail)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("LEAD"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();
        UUID invitationId = UUID.fromString(body(inviteResult1).path("invitationId").asText());

        // Verify invitation email was sent and extract the raw token
        String rawToken = awaitToken(inviteeEmail, "You've been invited to join " + manager.workspaceName() + " on Adept");
        assertThat(rawToken).isNotEmpty();

        // 2. Manager assigns second repository - reuses pending invitation
        invite(manager.token(), repo2, inviteeEmail)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.invitationId").value(invitationId.toString()));

        // 3. Preview endpoint verifies safe data and omits secrets/hashes
        MvcResult previewResult = mockMvc.perform(get("/api/v1/invitations/preview")
                .param("token", rawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(inviteeEmail))
            .andExpect(jsonPath("$.workspaceName").value(manager.workspaceName()))
            .andExpect(jsonPath("$.role").value("LEAD"))
            .andExpect(jsonPath("$.existingAccount").value(false))
            .andExpect(jsonPath("$.repositories").isArray())
            .andExpect(jsonPath("$.repositories[0]").value("adept-invite-test/api-core"))
            .andExpect(jsonPath("$.repositories[1]").value("adept-invite-test/web-app"))
            .andExpect(jsonPath("$.tokenHash").doesNotExist())
            .andExpect(jsonPath("$.invitedBy").doesNotExist())
            .andReturn();

        JsonNode previewBody = body(previewResult);
        assertThat(previewBody.has("tokenHash")).isFalse();
        assertThat(previewBody.has("token")).isFalse();

        // 4. Accept invitation as a new user
        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult acceptResult = mockMvc.perform(post("/api/v1/invitations/accept")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "token": "%s",
                        "displayName": "New Lead Member",
                        "password": "%s"
                    }
                    """.formatted(rawToken, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(false))
            .andExpect(jsonPath("$.user.email").value(inviteeEmail))
            .andExpect(jsonPath("$.user.displayName").value("New Lead Member"))
            .andExpect(jsonPath("$.user.emailVerified").value(true))
            .andExpect(jsonPath("$.currentMembership.role").value("LEAD"))
            .andExpect(jsonPath("$.currentMembership.workspaceName").value(manager.workspaceName()))
            .andReturn();

        // Verify DB state after acceptance
        UUID newUserId = jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?",
            UUID.class,
            inviteeEmail
        );
        assertThat(newUserId).isNotNull();

        UUID membershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ? AND role = 'LEAD' AND status = 'ACTIVE'",
            UUID.class,
            manager.workspaceId(),
            newUserId
        );
        assertThat(membershipId).isNotNull();

        // Verify both repository lead assignments now reference the new membership and have NULL invitation_id
        long assignmentCount = rowCount(
            "repository_lead_assignments",
            "workspace_id = ? AND lead_membership_id = ? AND invitation_id IS NULL",
            manager.workspaceId(),
            membershipId
        );
        assertThat(assignmentCount).isEqualTo(2);

        // Verify invitation status is ACCEPTED
        String invStatus = jdbc.queryForObject(
            "SELECT status FROM workspace_invitations WHERE id = ?",
            String.class,
            invitationId
        );
        assertThat(invStatus).isEqualTo("ACCEPTED");

        // 5. One-use acceptance: attempting to accept again must fail
        CsrfPair csrf2 = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/invitations/accept")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf2.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf2.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "token": "%s",
                        "displayName": "Replay Attack",
                        "password": "%s"
                    }
                    """.formatted(rawToken, VALID_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));

        // Previewing accepted invitation also fails
        mockMvc.perform(get("/api/v1/invitations/preview")
                .param("token", rawToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void expiredInvitationCannotBePreviewedOrAccepted() throws Exception {
        ManagerSession manager = createManager("expired-mgr");
        UUID repo = insertRepository(manager.workspaceId(), manager.membershipId(), "expired-repo");
        String inviteeEmail = uniqueEmail("expired-invitee").toLowerCase(Locale.ROOT);

        mailSender.reset();
        invite(manager.token(), repo, inviteeEmail).andExpect(status().isOk());
        String rawToken = awaitToken(inviteeEmail, "You've been invited to join " + manager.workspaceName() + " on Adept");

        // Expire the invitation in the database
        jdbc.update("UPDATE workspace_invitations SET expires_at = now() - interval '1 hour' WHERE email = ?", inviteeEmail);

        // Preview should fail with INVITATION_EXPIRED
        mockMvc.perform(get("/api/v1/invitations/preview")
                .param("token", rawToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));

        // Accept should fail with INVITATION_EXPIRED
        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/invitations/accept")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "token": "%s",
                        "displayName": "Expired User",
                        "password": "%s"
                    }
                    """.formatted(rawToken, VALID_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
    }

    @Test
    void existingUserCanAcceptInvitationWithPasswordOrAuthenticatedSession() throws Exception {
        ManagerSession manager = createManager("exist-mgr");
        UUID repo = insertRepository(manager.workspaceId(), manager.membershipId(), "exist-repo");
        String existingEmail = uniqueEmail("existing-user").toLowerCase(Locale.ROOT);

        UUID existingUserId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status, email_verified_at,
                token_version, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'Existing Developer', 'ACTIVE', now(), 0, now(), now(), 0)
            """, existingUserId, existingEmail, passwordService.encodeNewPassword(VALID_PASSWORD));

        mailSender.reset();
        invite(manager.token(), repo, existingEmail).andExpect(status().isOk());
        String rawToken = awaitToken(existingEmail, "You've been invited to join " + manager.workspaceName() + " on Adept");

        // Preview shows existingAccount = true
        mockMvc.perform(get("/api/v1/invitations/preview")
                .param("token", rawToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.existingAccount").value(true));

        // Accept with password
        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/invitations/accept")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "token": "%s",
                        "password": "%s"
                    }
                    """.formatted(rawToken, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.email").value(existingEmail))
            .andExpect(jsonPath("$.currentMembership.role").value("LEAD"));

        // Verify membership role in workspace is LEAD
        UUID memId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE workspace_id = ? AND user_id = ?",
            UUID.class,
            manager.workspaceId(),
            existingUserId
        );
        assertThat(memId).isNotNull();
    }

    @Test
    void resendGeneratesNewTokenAndInvalidatesOldToken() throws Exception {
        ManagerSession manager = createManager("resend-mgr");
        UUID repo = insertRepository(manager.workspaceId(), manager.membershipId(), "resend-repo");
        String inviteeEmail = uniqueEmail("resend-invitee").toLowerCase(Locale.ROOT);

        mailSender.reset();
        MvcResult inviteResult = invite(manager.token(), repo, inviteeEmail).andExpect(status().isOk()).andReturn();
        UUID invitationId = UUID.fromString(body(inviteResult).path("invitationId").asText());
        String initialToken = awaitToken(inviteeEmail, "You've been invited to join " + manager.workspaceName() + " on Adept");

        mailSender.reset();

        // Resend invitation
        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/resend")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token())))
            .andExpect(status().isNoContent());

        String resentToken = awaitToken(inviteeEmail, "You've been invited to join " + manager.workspaceName() + " on Adept");
        assertThat(resentToken).isNotEqualTo(initialToken);

        // Old token is invalid
        mockMvc.perform(get("/api/v1/invitations/preview")
                .param("token", initialToken))
            .andExpect(status().isNotFound());

        // New token is valid
        mockMvc.perform(get("/api/v1/invitations/preview")
                .param("token", resentToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(inviteeEmail));
    }

    @Test
    void revokeInvitationDeletesPendingAssignmentsAndPreventsAcceptance() throws Exception {
        ManagerSession manager = createManager("revoke-mgr");
        UUID repo = insertRepository(manager.workspaceId(), manager.membershipId(), "revoke-repo");
        String inviteeEmail = uniqueEmail("revoke-invitee").toLowerCase(Locale.ROOT);

        mailSender.reset();
        MvcResult inviteResult = invite(manager.token(), repo, inviteeEmail).andExpect(status().isOk()).andReturn();
        UUID invitationId = UUID.fromString(body(inviteResult).path("invitationId").asText());
        String rawToken = awaitToken(inviteeEmail, "You've been invited to join " + manager.workspaceName() + " on Adept");

        assertThat(rowCount("repository_lead_assignments", "invitation_id = ?", invitationId)).isOne();

        // Revoke invitation
        CsrfPair csrf = fetchCsrf(mockMvc);
        mockMvc.perform(delete("/api/v1/invitations/" + invitationId)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + manager.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token())))
            .andExpect(status().isNoContent());

        // Verify assignment deleted
        assertThat(rowCount("repository_lead_assignments", "invitation_id = ?", invitationId)).isZero();

        // Status is REVOKED
        String status = jdbc.queryForObject("SELECT status FROM workspace_invitations WHERE id = ?", String.class, invitationId);
        assertThat(status).isEqualTo("REVOKED");

        // Previewing revoked invitation fails
        mockMvc.perform(get("/api/v1/invitations/preview")
                .param("token", rawToken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void nonManagerCannotResendOrRevokeInvitations() throws Exception {
        ManagerSession manager = createManager("perm-mgr");
        ManagerSession other = createManager("other-mgr");
        UUID repo = insertRepository(manager.workspaceId(), manager.membershipId(), "perm-repo");
        String inviteeEmail = uniqueEmail("perm-invitee").toLowerCase(Locale.ROOT);

        MvcResult inviteResult = invite(manager.token(), repo, inviteeEmail).andExpect(status().isOk()).andReturn();
        UUID invitationId = UUID.fromString(body(inviteResult).path("invitationId").asText());

        CsrfPair csrf = fetchCsrf(mockMvc);

        // Other workspace manager cannot resend -> 404 (scoped to workspace)
        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/resend")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + other.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));

        // Lead role cannot resend -> 403 MANAGER_REQUIRED
        jdbc.update("UPDATE memberships SET role = 'LEAD' WHERE id = ?", manager.membershipId());
        String leadToken = jwtService.issue(new AuthenticatedPrincipal(
            manager.userId(),
            manager.membershipId(),
            manager.workspaceId(),
            MembershipRole.LEAD,
            0
        ));

        mockMvc.perform(post("/api/v1/invitations/" + invitationId + "/resend")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + leadToken)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(new Cookie("XSRF-TOKEN", csrf.token())))
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
        String workspaceName = "Test Workspace " + prefix;
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail(prefix),
                VALID_PASSWORD,
                "Invitation Manager " + prefix,
                workspaceName,
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
        return new ManagerSession(signup.user().id(), signup.workspace().id(), workspaceName, membershipId, token);
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
        String workspaceName,
        UUID membershipId,
        String token
    ) {
    }
}
