package com.adept.api.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;
import com.adept.api.common.domain.WorkspaceStatus;
import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ConflictException;
import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.security.JwtService;
import com.adept.api.workspace.WorkspaceService;
import com.adept.api.workspace.dto.DeleteWorkspaceRequest;
import com.adept.api.workspace.dto.WorkspaceDeletionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceDeletionIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void invalidCredentialsAndLockedAuthorizationChangesHaveNoSideEffects() throws Exception {
        SignupResponse reauthentication = verifiedSignup("invalid-delete", "Reauthentication Workspace");
        String accessToken = loginAndGetAccessToken(reauthentication.user().email());

        requestDeletion(accessToken, "wrong-slug")
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        requestPasswordReauthentication(accessToken, "wrong-password")
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_FAILED"));
        assertNoDeletionSideEffects(reauthentication.workspace().id());

        SignupResponse tokenMismatch = verifiedSignup("token-mismatch-delete", "Token Mismatch Workspace");
        AuthenticatedPrincipal staleTokenPrincipal = managerPrincipal(tokenMismatch);
        jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", tokenMismatch.user().id());

        assertThatThrownBy(() -> workspaceService.deleteCurrentWorkspace(
            staleTokenPrincipal,
            new DeleteWorkspaceRequest(tokenMismatch.workspace().slug()),
            requestContext()
        )).isInstanceOfSatisfying(
            UnauthorizedException.class,
            exception -> assertThat(exception.code()).isEqualTo(ProblemCode.SESSION_INVALID)
        );
        assertNoDeletionSideEffects(tokenMismatch.workspace().id());

        SignupResponse roleDowngrade = verifiedSignup("role-downgrade-delete", "Role Downgrade Workspace");
        AuthenticatedPrincipal staleRolePrincipal = managerPrincipal(roleDowngrade);
        jdbc.update("UPDATE memberships SET role = 'LEAD' WHERE id = ?", staleRolePrincipal.membershipId());

        assertThatThrownBy(() -> workspaceService.deleteCurrentWorkspace(
            staleRolePrincipal,
            new DeleteWorkspaceRequest(roleDowngrade.workspace().slug()),
            requestContext()
        )).isInstanceOfSatisfying(
            ForbiddenException.class,
            exception -> assertThat(exception.code()).isEqualTo(ProblemCode.MANAGER_REQUIRED)
        );
        assertNoDeletionSideEffects(roleDowngrade.workspace().id());
    }

    @Test
    void staleSessionRequiresPasswordReauthenticationBeforeDeletion() throws Exception {
        SignupResponse signup = verifiedSignup("recent-password-delete", "Recent Password Workspace");
        AuthenticatedPrincipal current = managerPrincipal(signup);
        AuthenticatedPrincipal stale = new AuthenticatedPrincipal(
            current.userId(),
            current.membershipId(),
            current.workspaceId(),
            current.role(),
            current.tokenVersion(),
            Instant.now().minusSeconds(601)
        );
        String staleAccessToken = jwtService.issue(stale);

        requestDeletion(staleAccessToken, signup.workspace().slug())
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
        assertNoDeletionSideEffects(signup.workspace().id());

        MvcResult reauthentication = requestPasswordReauthentication(staleAccessToken, VALID_PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.hasPassword").value(true))
            .andReturn();
        String freshAccessToken = objectMapper.readTree(reauthentication.getResponse().getContentAsString())
            .path("accessToken")
            .asText();

        requestDeletion(freshAccessToken, signup.workspace().slug())
            .andExpect(status().isAccepted());
    }

    @Test
    void concurrentDeletionCreatesOneAtomicLifecycleAndInvalidatesTheJwt() throws Exception {
        SignupResponse signup = verifiedSignup("concurrent-delete", "Concurrent Deletion Workspace");
        UUID workspaceId = signup.workspace().id();
        UUID githubIntegrationId = insertGithubIntegration(workspaceId);
        UUID jiraIntegrationId = insertJiraIntegration(workspaceId);
        String accessToken = loginAndGetAccessToken(signup.user().email());
        AuthenticatedPrincipal principal = managerPrincipal(signup);
        DeleteWorkspaceRequest request = new DeleteWorkspaceRequest(signup.workspace().slug());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> attemptDeletion(principal, request, ready, start));
            Future<Object> second = executor.submit(() -> attemptDeletion(principal, request, ready, start));
            ready.await();
            start.countDown();

            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes).filteredOn(WorkspaceDeletionResponse.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(ConflictException.class::isInstance).hasSize(1);
            ApiException conflict = (ApiException) outcomes.stream()
                .filter(ConflictException.class::isInstance)
                .findFirst()
                .orElseThrow();
            assertThat(conflict.code()).isEqualTo(ProblemCode.WORKSPACE_DELETION_ALREADY_REQUESTED);
        } finally {
            executor.shutdownNow();
        }

        assertThat(workspaceStatus(workspaceId)).isEqualTo(WorkspaceStatus.DELETING.name());
        assertThat(jdbc.queryForObject(
            "SELECT status FROM github_integrations WHERE id = ?",
            String.class,
            githubIntegrationId
        )).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM jira_integrations WHERE id = ?",
            String.class,
            jiraIntegrationId
        )).isEqualTo("SUSPENDED");

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM processing_jobs WHERE job_type = 'DELETE_WORKSPACE' "
                + "AND workspace_id IS NULL AND repository_id IS NULL AND raw_event_id IS NULL "
                + "AND payload ->> 'workspaceId' = ?",
            Integer.class,
            workspaceId.toString()
        )).isEqualTo(1);
        assertThat(countDeletionAudits(workspaceId)).isEqualTo(1);

        mockMvc.perform(get("/api/v1/workspaces/current")
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
    }

    @Test
    void refreshFamilyRemainsUsableForAnotherWorkspaceAfterDeletion() throws Exception {
        SignupResponse signup = verifiedSignup("refresh-after-delete", "Workspace To Delete");
        UUID remainingWorkspaceId = UUID.randomUUID();
        UUID remainingMembershipId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO workspaces (id, name, slug, timezone, status, created_at, updated_at, version) "
                + "VALUES (?, 'Remaining Workspace', ?, 'UTC', 'ACTIVE', now(), now(), 0)",
            remainingWorkspaceId,
            "remaining-" + UUID.randomUUID().toString().substring(0, 8)
        );
        jdbc.update(
            "INSERT INTO memberships (id, user_id, workspace_id, role, status, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, ?, ?, now(), now(), 0)",
            remainingMembershipId,
            signup.user().id(),
            remainingWorkspaceId,
            MembershipRole.LEAD.name(),
            MembershipStatus.ACTIVE.name()
        );

        CsrfPair loginCsrf = fetchCsrf(mockMvc);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", loginCsrf.token())
                .cookie(loginCsrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(signup.user().email(), VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(true))
            .andReturn();
        Cookie refreshCookie = login.getResponse().getCookie("adept_refresh");
        assertThat(refreshCookie).isNotNull();

        CsrfPair switchCsrf = fetchCsrf(mockMvc);
        MvcResult switched = mockMvc.perform(post("/api/v1/auth/switch-workspace/" + signup.workspace().id())
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", switchCsrf.token())
                .cookie(switchCsrf.cookie())
                .cookie(refreshCookie))
            .andExpect(status().isOk())
            .andReturn();
        String accessToken = objectMapper.readTree(switched.getResponse().getContentAsString())
            .get("accessToken")
            .asText();

        requestDeletion(accessToken, signup.workspace().slug())
            .andExpect(status().isAccepted());

        CsrfPair refreshCsrf = fetchCsrf(mockMvc);
        mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", refreshCsrf.token())
                .cookie(refreshCsrf.cookie())
                .cookie(refreshCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspaceSelectionRequired").value(false))
            .andExpect(jsonPath("$.currentMembership.workspaceId").value(remainingWorkspaceId.toString()))
            .andExpect(jsonPath("$.accessToken").isString());
    }

    private ResultActions requestDeletion(String accessToken, String confirmationSlug)
            throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        return mockMvc.perform(delete("/api/v1/workspaces/current")
            .header("Origin", FRONTEND_ORIGIN)
            .header("Authorization", "Bearer " + accessToken)
            .header("X-XSRF-TOKEN", csrf.token())
            .cookie(csrf.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"confirmationSlug":"%s"}
                """.formatted(confirmationSlug)));
    }

    private ResultActions requestPasswordReauthentication(String accessToken, String password)
            throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        return mockMvc.perform(post("/api/v1/auth/reauthenticate/password")
            .header("Origin", FRONTEND_ORIGIN)
            .header("Authorization", "Bearer " + accessToken)
            .header("X-XSRF-TOKEN", csrf.token())
            .cookie(csrf.cookie())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"password":"%s"}
                """.formatted(password)));
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        CsrfPair csrf = fetchCsrf(mockMvc);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                .header("Origin", FRONTEND_ORIGIN)
                .header("X-XSRF-TOKEN", csrf.token())
                .cookie(csrf.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, VALID_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private SignupResponse verifiedSignup(String emailPrefix, String workspaceName) {
        SignupResponse signup = authService.signup(
            new SignupRequest(uniqueEmail(emailPrefix), VALID_PASSWORD, "Deletion Manager", workspaceName, "UTC"),
            requestContext()
        );
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());
        return signup;
    }

    private AuthenticatedPrincipal managerPrincipal(SignupResponse signup) {
        UUID membershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE user_id = ? AND workspace_id = ?",
            UUID.class,
            signup.user().id(),
            signup.workspace().id()
        );
        Integer tokenVersion = jdbc.queryForObject(
            "SELECT token_version FROM users WHERE id = ?",
            Integer.class,
            signup.user().id()
        );
        return new AuthenticatedPrincipal(
            signup.user().id(),
            membershipId,
            signup.workspace().id(),
            MembershipRole.MANAGER,
            tokenVersion,
            Instant.now()
        );
    }

    private Object attemptDeletion(
            AuthenticatedPrincipal principal,
            DeleteWorkspaceRequest request,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return workspaceService.deleteCurrentWorkspace(principal, request, requestContext());
        } catch (ApiException exception) {
            return exception;
        }
    }

    private UUID insertGithubIntegration(UUID workspaceId) {
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO github_integrations (
                id, workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, created_at, updated_at, version
            ) VALUES (?, ?, ?, 100, 'test-gh-org', 'ORGANIZATION', 'ALL', 'ACTIVE', now(), now(), 0)
            """, integrationId, workspaceId, System.currentTimeMillis());
        return integrationId;
    }

    private UUID insertJiraIntegration(UUID workspaceId) {
        UUID integrationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO jira_integrations (
                id, workspace_id, cloud_id, site_url, display_name, access_token_enc,
                refresh_token_enc, encryption_key_version, access_token_expires_at,
                status, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'https://test.atlassian.net', 'Test Jira', 'enc_acc',
                'enc_ref', 1, now() + interval '1 hour', 'ACTIVE', now(), now(), 0)
            """, integrationId, workspaceId, "cloud-" + UUID.randomUUID());
        return integrationId;
    }

    private void assertNoDeletionSideEffects(UUID workspaceId) {
        assertThat(workspaceStatus(workspaceId)).isEqualTo(WorkspaceStatus.ACTIVE.name());
        assertThat(countDeletionJobs(workspaceId)).isZero();
        assertThat(countDeletionAudits(workspaceId)).isZero();
    }

    private String workspaceStatus(UUID workspaceId) {
        return jdbc.queryForObject(
            "SELECT status FROM workspaces WHERE id = ?",
            String.class,
            workspaceId
        );
    }

    private int countDeletionJobs(UUID workspaceId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM processing_jobs WHERE job_type = 'DELETE_WORKSPACE' "
                + "AND payload ->> 'workspaceId' = ?",
            Integer.class,
            workspaceId.toString()
        );
    }

    private int countDeletionAudits(UUID workspaceId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE workspace_id = ? AND action = 'WORKSPACE_DELETION_REQUESTED'",
            Integer.class,
            workspaceId
        );
    }
}
