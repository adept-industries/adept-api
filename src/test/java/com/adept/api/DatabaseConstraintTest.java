package com.adept.api;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = {
    "app.frontend-base-url=http://localhost:3000",
    "app.public-api-base-url=http://localhost:8080",
    "app.email-from=Adept Test <test@adept.local>",
    "app.jwt.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.token-hash-pepper-base64=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=",
    "app.integration-encryption.active-key-version=1",
    "app.integration-encryption.keys[1]=CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=",
    "app.github.app-id=1",
    "app.github.app-slug=adept-test",
    "app.github.private-key-base64=dGVzdC1vbmx5",
    "app.github.webhook-secret=test-only",
    "app.jira.client-id=test-only",
    "app.jira.client-secret=test-only",
    "app.jira.callback-url=http://localhost/callback",
    "app.engine.base-url=http://localhost:8000",
    "app.engine.internal-token=test-only",
    "spring.mail.host=localhost",
    "spring.mail.port=1025"
})
class DatabaseConstraintTest {

    private static final String INVALID_ENUM_VALUE = "INVALID";

    @Container
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("adept_constraints_test")
            .withUsername("adept")
            .withPassword("adept");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE workspaces, users CASCADE");
    }

    @Test
    void userEmailUniquenessIsCaseInsensitive() {
        insertUser("CaseSensitive@Example.com");

        assertThatThrownBy(() -> insertUser("casesensitive@example.COM"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyEnumCheckRejectsAnInvalidValue() {
        EnumFixture fixture = createEnumFixture();
        TenantFixture tenant = fixture.tenant();

        assertInvalidEnum("users.status",
            "UPDATE users SET status = ? WHERE id = ?", tenant.userId());
        assertInvalidEnum("workspaces.status",
            "UPDATE workspaces SET status = ? WHERE id = ?", tenant.workspaceId());
        assertInvalidEnum("memberships.role",
            "UPDATE memberships SET role = ? WHERE id = ?", tenant.managerMembershipId());
        assertInvalidEnum("memberships.status",
            "UPDATE memberships SET status = ? WHERE id = ?", tenant.managerMembershipId());
        assertInvalidEnum("workspace_invitations.role",
            "UPDATE workspace_invitations SET role = ? WHERE id = ?", fixture.invitationId());
        assertInvalidEnum("workspace_invitations.status",
            "UPDATE workspace_invitations SET status = ? WHERE id = ?", fixture.invitationId());
        assertInvalidEnum("user_action_tokens.purpose",
            "UPDATE user_action_tokens SET purpose = ? WHERE id = ?", fixture.actionTokenId());
        assertInvalidEnum("external_identities.provider",
            "UPDATE external_identities SET provider = ? WHERE id = ?", fixture.externalIdentityId());
        assertInvalidEnum("integration_oauth_states.provider",
            "UPDATE integration_oauth_states SET provider = ? WHERE id = ?", fixture.oauthStateId());
        assertInvalidEnum("github_integrations.account_type",
            "UPDATE github_integrations SET account_type = ? WHERE id = ?",
            tenant.githubIntegrationId());
        assertInvalidEnum("github_integrations.repository_selection",
            "UPDATE github_integrations SET repository_selection = ? WHERE id = ?",
            tenant.githubIntegrationId());
        assertInvalidEnum("github_integrations.status",
            "UPDATE github_integrations SET status = ? WHERE id = ?", tenant.githubIntegrationId());
        assertInvalidEnum("repositories.visibility",
            "UPDATE repositories SET visibility = ? WHERE id = ?", tenant.repositoryId());
        assertInvalidEnum("jira_integrations.status",
            "UPDATE jira_integrations SET status = ? WHERE id = ?", fixture.jiraIntegrationId());
        assertInvalidEnum("raw_webhook_events.source",
            "UPDATE raw_webhook_events SET source = ? WHERE id = ?", fixture.rawEventId());
        assertInvalidEnum("raw_webhook_events.status",
            "UPDATE raw_webhook_events SET status = ? WHERE id = ?", fixture.rawEventId());
        assertInvalidEnum("processing_jobs.job_type",
            "UPDATE processing_jobs SET job_type = ? WHERE id = ?", fixture.processingJobId());
        assertInvalidEnum("processing_jobs.status",
            "UPDATE processing_jobs SET status = ? WHERE id = ?", fixture.processingJobId());
        assertInvalidEnum("pull_requests.state",
            "UPDATE pull_requests SET state = ? WHERE id = ?", fixture.pullRequestId());
        assertInvalidEnum("deployments.source",
            "UPDATE deployments SET source = ? WHERE id = ?", fixture.deploymentId());
        assertInvalidEnum("deployments.status",
            "UPDATE deployments SET status = ? WHERE id = ?", fixture.deploymentId());
        assertInvalidEnum("deployment_pull_requests.link_method",
            "UPDATE deployment_pull_requests SET link_method = ? "
                + "WHERE deployment_id = ? AND pull_request_id = ?",
            fixture.deploymentId(), fixture.pullRequestId());
        assertInvalidEnum("pull_request_issue_links.link_source",
            "UPDATE pull_request_issue_links SET link_source = ? "
                + "WHERE pull_request_id = ? AND jira_issue_id = ?",
            fixture.pullRequestId(), fixture.jiraIssueId());
        assertInvalidEnum("incidents.source",
            "UPDATE incidents SET source = ? WHERE id = ?", fixture.incidentId());
        assertInvalidEnum("incidents.severity",
            "UPDATE incidents SET severity = ? WHERE id = ?", fixture.incidentId());
        assertInvalidEnum("incidents.status",
            "UPDATE incidents SET status = ? WHERE id = ?", fixture.incidentId());
        assertInvalidEnum("metric_snapshots.metric_type",
            "UPDATE metric_snapshots SET metric_type = ? WHERE id = ?", fixture.metricSnapshotId());
        assertInvalidEnum("metric_snapshots.granularity",
            "UPDATE metric_snapshots SET granularity = ? WHERE id = ?", fixture.metricSnapshotId());
        assertInvalidEnum("risk_predictions.risk_level",
            "UPDATE risk_predictions SET risk_level = ? WHERE id = ?", fixture.riskPredictionId());
        assertInvalidEnum("alert_rules.metric_type",
            "UPDATE alert_rules SET metric_type = ? WHERE id = ?", fixture.alertRuleId());
        assertInvalidEnum("alert_rules.comparator",
            "UPDATE alert_rules SET comparator = ? WHERE id = ?", fixture.alertRuleId());
        assertInvalidEnum("alert_rules.channel",
            "UPDATE alert_rules SET channel = ? WHERE id = ?", fixture.alertRuleId());
        assertInvalidEnum("notification_deliveries.channel",
            "UPDATE notification_deliveries SET channel = ? WHERE id = ?",
            fixture.notificationDeliveryId());
        assertInvalidEnum("notification_deliveries.status",
            "UPDATE notification_deliveries SET status = ? WHERE id = ?",
            fixture.notificationDeliveryId());
    }

    @Test
    void deletingWorkspaceCascadesAllRepresentativeTenantRowsButKeepsTheUser() {
        EnumFixture fixture = createEnumFixture();
        TenantFixture tenant = fixture.tenant();

        assertThat(jdbc.update("DELETE FROM workspaces WHERE id = ?", tenant.workspaceId())).isOne();

        assertThat(rowCount("workspaces", tenant.workspaceId())).isZero();
        assertThat(rowCount("memberships", tenant.managerMembershipId())).isZero();
        assertThat(rowCount("repositories", tenant.repositoryId())).isZero();
        assertThat(rowCount("raw_webhook_events", fixture.rawEventId())).isZero();
        assertThat(rowCount("processing_jobs", fixture.processingJobId())).isZero();
        assertThat(rowCount("risk_predictions", fixture.riskPredictionId())).isZero();
        assertThat(rowCount("users", tenant.userId())).isOne();
    }

    @Test
    void leadAssignmentRejectsBothTargets() {
        AssignmentFixture fixture = createAssignmentFixture();

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, invitation_id,
                assigned_by_membership_id
            ) VALUES (?, ?, ?, ?, ?)
            """,
            fixture.tenant().workspaceId(), fixture.tenant().repositoryId(),
            fixture.leadMembershipId(), fixture.invitationId(),
            fixture.tenant().managerMembershipId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leadAssignmentRejectsNoTarget() {
        AssignmentFixture fixture = createAssignmentFixture();

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, assigned_by_membership_id
            ) VALUES (?, ?, ?)
            """,
            fixture.tenant().workspaceId(), fixture.tenant().repositoryId(),
            fixture.tenant().managerMembershipId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leadAssignmentAcceptsEitherSingleTarget() {
        AssignmentFixture fixture = createAssignmentFixture();

        UUID membershipAssignmentId = jdbc.queryForObject("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            RETURNING id
            """, UUID.class,
            fixture.tenant().workspaceId(), fixture.tenant().repositoryId(),
            fixture.leadMembershipId(), fixture.tenant().managerMembershipId());

        assertThat(membershipAssignmentId).isNotNull();
        jdbc.update("DELETE FROM repository_lead_assignments WHERE id = ?", membershipAssignmentId);

        UUID invitationAssignmentId = jdbc.queryForObject("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, invitation_id, assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            RETURNING id
            """, UUID.class,
            fixture.tenant().workspaceId(), fixture.tenant().repositoryId(),
            fixture.invitationId(), fixture.tenant().managerMembershipId());

        assertThat(invitationAssignmentId).isNotNull();
    }

    @Test
    void duplicateWebhookDeliveryIsRejected() {
        TenantFixture tenant = createTenantFixture();
        insertRawEvent(tenant, "duplicate-delivery");

        assertThatThrownBy(() -> insertRawEvent(tenant, "duplicate-delivery"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicatePredictionForPullRequestAndModelVersionIsRejected() {
        TenantFixture tenant = createTenantFixture();
        UUID pullRequestId = insertPullRequest(tenant);
        insertRiskPrediction(tenant, pullRequestId, "adept-risk", "1.0.0");

        assertThatThrownBy(() ->
            insertRiskPrediction(tenant, pullRequestId, "adept-risk", "1.0.0"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void processingJobIsUniqueForRawEventAndJobType() {
        TenantFixture tenant = createTenantFixture();
        UUID rawEventId = insertRawEvent(tenant, "job-delivery");
        insertProcessingJob(tenant, rawEventId, "PROCESS_GITHUB_EVENT");

        assertThat(insertProcessingJob(tenant, rawEventId, "RECALCULATE_METRICS")).isNotNull();
        assertThatThrownBy(() ->
            insertProcessingJob(tenant, rawEventId, "PROCESS_GITHUB_EVENT"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingLeadMembershipTargetDeletesAssignment() {
        AssignmentFixture fixture = createAssignmentFixture();
        UUID assignmentId = insertMembershipAssignment(fixture);

        assertThat(jdbc.update(
            "DELETE FROM memberships WHERE id = ?", fixture.leadMembershipId()
        )).isOne();

        assertThat(rowCount("repository_lead_assignments", assignmentId)).isZero();
    }

    @Test
    void deletingInvitationTargetDeletesAssignment() {
        AssignmentFixture fixture = createAssignmentFixture();
        UUID assignmentId = insertInvitationAssignment(fixture);

        assertThat(jdbc.update(
            "DELETE FROM workspace_invitations WHERE id = ?", fixture.invitationId()
        )).isOne();

        assertThat(rowCount("repository_lead_assignments", assignmentId)).isZero();
    }

    @Test
    void deletingAssignedByMembershipKeepsAssignmentAndClearsProvenance() {
        AssignmentFixture fixture = createAssignmentFixture();
        UUID assignmentId = insertMembershipAssignment(fixture);

        assertThat(jdbc.update(
            "DELETE FROM memberships WHERE id = ?",
            fixture.tenant().managerMembershipId()
        )).isOne();

        assertThat(rowCount("repository_lead_assignments", assignmentId)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT assigned_by_membership_id IS NULL
            FROM repository_lead_assignments
            WHERE id = ?
            """, Boolean.class, assignmentId)).isTrue();
    }

    private UUID insertMembershipAssignment(AssignmentFixture fixture) {
        return jdbc.queryForObject("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id,
                assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            RETURNING id
            """, UUID.class,
            fixture.tenant().workspaceId(),
            fixture.tenant().repositoryId(),
            fixture.leadMembershipId(),
            fixture.tenant().managerMembershipId());
    }

    private UUID insertInvitationAssignment(AssignmentFixture fixture) {
        return jdbc.queryForObject("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, invitation_id,
                assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            RETURNING id
            """, UUID.class,
            fixture.tenant().workspaceId(),
            fixture.tenant().repositoryId(),
            fixture.invitationId(),
            fixture.tenant().managerMembershipId());
    }

    private void assertInvalidEnum(String column, String sql, Object... identifyingArguments) {
        Object[] arguments = new Object[identifyingArguments.length + 1];
        arguments[0] = INVALID_ENUM_VALUE;
        System.arraycopy(identifyingArguments, 0, arguments, 1, identifyingArguments.length);

        assertThatThrownBy(() -> jdbc.update(sql, arguments))
            .as("invalid value for %s", column)
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int rowCount(String table, UUID id) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private UUID insertUser(String email) {
        return jdbc.queryForObject("""
            INSERT INTO users (email, password_hash, display_name)
            VALUES (?, 'test-password-hash', 'Test User')
            RETURNING id
            """, UUID.class, email);
    }

    private TenantFixture createTenantFixture() {
        UUID userId = insertUser("manager@example.com");
        UUID workspaceId = jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone)
            VALUES ('Test Workspace', 'test-workspace', 'UTC')
            RETURNING id
            """, UUID.class);
        UUID managerMembershipId = jdbc.queryForObject("""
            INSERT INTO memberships (workspace_id, user_id, role, status)
            VALUES (?, ?, 'MANAGER', 'ACTIVE')
            RETURNING id
            """, UUID.class, workspaceId, userId);
        UUID githubIntegrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, 1001, 2001, 'adept-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, managerMembershipId);
        UUID repositoryId = jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility
            ) VALUES (?, ?, 3001, 'adept-test', 'api', 'adept-test/api', 'main', 'PRIVATE')
            RETURNING id
            """, UUID.class, workspaceId, githubIntegrationId);

        return new TenantFixture(
            userId, workspaceId, managerMembershipId, githubIntegrationId, repositoryId);
    }

    private AssignmentFixture createAssignmentFixture() {
        TenantFixture tenant = createTenantFixture();
        UUID leadUserId = insertUser("lead@example.com");
        UUID leadMembershipId = jdbc.queryForObject("""
            INSERT INTO memberships (workspace_id, user_id, role, status)
            VALUES (?, ?, 'LEAD', 'ACTIVE')
            RETURNING id
            """, UUID.class, tenant.workspaceId(), leadUserId);
        UUID invitationId = insertInvitation(tenant.workspaceId(), tenant.managerMembershipId());

        return new AssignmentFixture(tenant, leadMembershipId, invitationId);
    }

    private UUID insertInvitation(UUID workspaceId, UUID managerMembershipId) {
        return jdbc.queryForObject("""
            INSERT INTO workspace_invitations (
                workspace_id, email, role, token_hash, status,
                invited_by_membership_id, expires_at
            ) VALUES (?, 'invited@example.com', 'LEAD', 'invitation-token-hash',
                      'PENDING', ?, now() + interval '1 day')
            RETURNING id
            """, UUID.class, workspaceId, managerMembershipId);
    }

    private UUID insertRawEvent(TenantFixture tenant, String deliveryId) {
        return jdbc.queryForObject("""
            INSERT INTO raw_webhook_events (
                workspace_id, repository_id, source, delivery_id, event_type, payload, status
            ) VALUES (?, ?, 'GITHUB', ?, 'pull_request', '{}'::jsonb, 'RECEIVED')
            RETURNING id
            """, UUID.class, tenant.workspaceId(), tenant.repositoryId(), deliveryId);
    }

    private UUID insertProcessingJob(TenantFixture tenant, UUID rawEventId, String jobType) {
        return jdbc.queryForObject("""
            INSERT INTO processing_jobs (
                workspace_id, repository_id, raw_event_id, job_type, status
            ) VALUES (?, ?, ?, ?, 'PENDING')
            RETURNING id
            """, UUID.class,
            tenant.workspaceId(), tenant.repositoryId(), rawEventId, jobType);
    }

    private UUID insertPullRequest(TenantFixture tenant) {
        return jdbc.queryForObject("""
            INSERT INTO pull_requests (
                workspace_id, repository_id, github_pr_id, number, title, state,
                base_ref, head_ref, opened_at
            ) VALUES (?, ?, 4001, 1, 'Test pull request', 'OPEN',
                      'main', 'feature/test', now())
            RETURNING id
            """, UUID.class, tenant.workspaceId(), tenant.repositoryId());
    }

    private UUID insertRiskPrediction(
        TenantFixture tenant,
        UUID pullRequestId,
        String modelName,
        String modelVersion
    ) {
        return jdbc.queryForObject("""
            INSERT INTO risk_predictions (
                workspace_id, repository_id, pull_request_id, model_name,
                model_version, risk_score, risk_level
            ) VALUES (?, ?, ?, ?, ?, 0.25, 'LOW')
            RETURNING id
            """, UUID.class,
            tenant.workspaceId(), tenant.repositoryId(), pullRequestId, modelName, modelVersion);
    }

    private EnumFixture createEnumFixture() {
        TenantFixture tenant = createTenantFixture();
        UUID invitationId = insertInvitation(tenant.workspaceId(), tenant.managerMembershipId());
        UUID actionTokenId = jdbc.queryForObject("""
            INSERT INTO user_action_tokens (user_id, purpose, token_hash, expires_at)
            VALUES (?, 'VERIFY_EMAIL', 'action-token-hash', now() + interval '1 hour')
            RETURNING id
            """, UUID.class, tenant.userId());
        UUID externalIdentityId = jdbc.queryForObject("""
            INSERT INTO external_identities (user_id, provider, provider_user_id, login)
            VALUES (?, 'GITHUB', 'provider-user-1', 'manager')
            RETURNING id
            """, UUID.class, tenant.userId());
        UUID oauthStateId = jdbc.queryForObject("""
            INSERT INTO integration_oauth_states (
                provider, workspace_id, initiated_by_membership_id, state_hash, expires_at
            ) VALUES ('GITHUB', ?, ?, 'oauth-state-hash', now() + interval '10 minutes')
            RETURNING id
            """, UUID.class, tenant.workspaceId(), tenant.managerMembershipId());
        UUID rawEventId = insertRawEvent(tenant, "enum-delivery");
        UUID processingJobId =
            insertProcessingJob(tenant, rawEventId, "PROCESS_GITHUB_EVENT");
        UUID pullRequestId = insertPullRequest(tenant);
        UUID deploymentId = jdbc.queryForObject("""
            INSERT INTO deployments (
                workspace_id, repository_id, source, external_deployment_id,
                environment, status, commit_sha
            ) VALUES (?, ?, 'MANUAL', 'deployment-1', 'production', 'QUEUED', 'abc123')
            RETURNING id
            """, UUID.class, tenant.workspaceId(), tenant.repositoryId());
        jdbc.update("""
            INSERT INTO deployment_pull_requests (deployment_id, pull_request_id, link_method)
            VALUES (?, ?, 'MANUAL')
            """, deploymentId, pullRequestId);
        UUID jiraIntegrationId = jdbc.queryForObject("""
            INSERT INTO jira_integrations (
                workspace_id, cloud_id, site_url, display_name, access_token_enc,
                refresh_token_enc, encryption_key_version, access_token_expires_at,
                status, connected_by_membership_id
            ) VALUES (?, 'cloud-1', 'https://adept.atlassian.net', 'Adept Jira',
                      'access-token', 'refresh-token', 1, now() + interval '1 hour',
                      'ACTIVE', ?)
            RETURNING id
            """, UUID.class, tenant.workspaceId(), tenant.managerMembershipId());
        UUID jiraProjectId = jdbc.queryForObject("""
            INSERT INTO jira_projects (
                workspace_id, jira_integration_id, jira_project_id,
                project_key, project_name
            ) VALUES (?, ?, 'jira-project-1', 'ADEPT', 'Adept')
            RETURNING id
            """, UUID.class, tenant.workspaceId(), jiraIntegrationId);
        UUID jiraIssueId = jdbc.queryForObject("""
            INSERT INTO jira_issues (
                workspace_id, jira_project_id, jira_issue_id, issue_key, summary
            ) VALUES (?, ?, 'jira-issue-1', 'ADEPT-1', 'Test issue')
            RETURNING id
            """, UUID.class, tenant.workspaceId(), jiraProjectId);
        jdbc.update("""
            INSERT INTO pull_request_issue_links (
                pull_request_id, jira_issue_id, link_source, confidence
            ) VALUES (?, ?, 'MANUAL', 1.0)
            """, pullRequestId, jiraIssueId);
        UUID incidentId = jdbc.queryForObject("""
            INSERT INTO incidents (
                workspace_id, repository_id, jira_issue_id, source, title,
                severity, status, detected_at
            ) VALUES (?, ?, ?, 'MANUAL', 'Test incident', 'UNKNOWN', 'OPEN', now())
            RETURNING id
            """, UUID.class, tenant.workspaceId(), tenant.repositoryId(), jiraIssueId);
        UUID metricSnapshotId = jdbc.queryForObject("""
            INSERT INTO metric_snapshots (
                workspace_id, repository_id, metric_type, granularity,
                period_start, period_end, value, unit, calculation_version
            ) VALUES (?, ?, 'DEPLOYMENT_FREQUENCY', 'DAY',
                      now() - interval '1 day', now(), 1.0, 'deployments/day', '1')
            RETURNING id
            """, UUID.class, tenant.workspaceId(), tenant.repositoryId());
        UUID riskPredictionId =
            insertRiskPrediction(tenant, pullRequestId, "enum-model", "1.0.0");
        UUID alertRuleId = jdbc.queryForObject("""
            INSERT INTO alert_rules (
                workspace_id, repository_id, created_by_membership_id, name,
                metric_type, comparator, threshold_value, channel, destination
            ) VALUES (?, ?, ?, 'High risk', 'PR_RISK_SCORE', 'GT', 0.75,
                      'EMAIL', 'alerts@example.com')
            RETURNING id
            """, UUID.class,
            tenant.workspaceId(), tenant.repositoryId(), tenant.managerMembershipId());
        UUID notificationDeliveryId = jdbc.queryForObject("""
            INSERT INTO notification_deliveries (
                workspace_id, repository_id, alert_rule_id, event_key,
                channel, destination, status
            ) VALUES (?, ?, ?, 'event-1', 'EMAIL', 'alerts@example.com', 'PENDING')
            RETURNING id
            """, UUID.class, tenant.workspaceId(), tenant.repositoryId(), alertRuleId);

        return new EnumFixture(
            tenant,
            invitationId,
            actionTokenId,
            externalIdentityId,
            oauthStateId,
            jiraIntegrationId,
            rawEventId,
            processingJobId,
            pullRequestId,
            deploymentId,
            jiraIssueId,
            incidentId,
            metricSnapshotId,
            riskPredictionId,
            alertRuleId,
            notificationDeliveryId
        );
    }

    private record TenantFixture(
        UUID userId,
        UUID workspaceId,
        UUID managerMembershipId,
        UUID githubIntegrationId,
        UUID repositoryId
    ) {
    }

    private record AssignmentFixture(
        TenantFixture tenant,
        UUID leadMembershipId,
        UUID invitationId
    ) {
    }

    private record EnumFixture(
        TenantFixture tenant,
        UUID invitationId,
        UUID actionTokenId,
        UUID externalIdentityId,
        UUID oauthStateId,
        UUID jiraIntegrationId,
        UUID rawEventId,
        UUID processingJobId,
        UUID pullRequestId,
        UUID deploymentId,
        UUID jiraIssueId,
        UUID incidentId,
        UUID metricSnapshotId,
        UUID riskPredictionId,
        UUID alertRuleId,
        UUID notificationDeliveryId
    ) {
    }
}
