package com.adept.api.auth;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.adept.api.audit.AuditAction;
import com.adept.api.audit.AuditService;
import com.adept.api.auth.dto.SignupRequest;
import com.adept.api.auth.dto.SignupResponse;
import com.adept.api.common.domain.MembershipRole;
import com.adept.api.security.AuthenticatedPrincipal;
import com.adept.api.workspace.WorkspaceService;
import com.adept.api.workspace.dto.DeleteWorkspaceRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

class WorkspaceDeletionRollbackIntegrationTest extends PartCIntegrationTestSupport {

    @Autowired
    private AuthService authService;

    @Autowired
    private WorkspaceService workspaceService;

    @MockitoBean
    private AuditService auditService;

    @Test
    void auditFailureRollsBackWorkspaceIntegrationsAndJob() {
        SignupResponse signup = authService.signup(
            new SignupRequest(
                uniqueEmail("rollback-delete"),
                VALID_PASSWORD,
                "Rollback Manager",
                "Rollback Workspace",
                "UTC"
            ),
            requestContext()
        );
        UUID workspaceId = signup.workspace().id();
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE id = ?", signup.user().id());

        UUID membershipId = jdbc.queryForObject(
            "SELECT id FROM memberships WHERE user_id = ? AND workspace_id = ?",
            UUID.class,
            signup.user().id(),
            workspaceId
        );
        Integer tokenVersion = jdbc.queryForObject(
            "SELECT token_version FROM users WHERE id = ?",
            Integer.class,
            signup.user().id()
        );
        UUID githubIntegrationId = UUID.randomUUID();
        UUID jiraIntegrationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO github_integrations (
                id, workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, created_at, updated_at, version
            ) VALUES (?, ?, ?, 100, 'rollback-org', 'ORGANIZATION', 'ALL', 'ACTIVE', now(), now(), 0)
            """, githubIntegrationId, workspaceId, System.currentTimeMillis());
        jdbc.update("""
            INSERT INTO jira_integrations (
                id, workspace_id, cloud_id, site_url, display_name, access_token_enc,
                refresh_token_enc, encryption_key_version, access_token_expires_at,
                status, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'https://rollback.atlassian.net', 'Rollback Jira', 'enc_acc',
                'enc_ref', 1, now() + interval '1 hour', 'ACTIVE', now(), now(), 0)
            """, jiraIntegrationId, workspaceId, "rollback-" + UUID.randomUUID());

        doThrow(new IllegalStateException("simulated audit persistence failure"))
            .when(auditService)
            .record(
                eq(AuditAction.WORKSPACE_DELETION_REQUESTED),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            );

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            signup.user().id(),
            membershipId,
            workspaceId,
            MembershipRole.MANAGER,
            tokenVersion,
            Instant.now()
        );

        assertThatThrownBy(() -> workspaceService.deleteCurrentWorkspace(
            principal,
            new DeleteWorkspaceRequest(signup.workspace().slug()),
            requestContext()
        )).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
            "SELECT status FROM workspaces WHERE id = ?",
            String.class,
            workspaceId
        )).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM github_integrations WHERE id = ?",
            String.class,
            githubIntegrationId
        )).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM jira_integrations WHERE id = ?",
            String.class,
            jiraIntegrationId
        )).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM processing_jobs WHERE job_type = 'DELETE_WORKSPACE'",
            Integer.class
        )).isZero();
    }
}
