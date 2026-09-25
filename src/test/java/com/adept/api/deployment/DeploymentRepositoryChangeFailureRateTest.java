package com.adept.api.deployment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import com.adept.api.auth.PartCIntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Change Failure Rate queries on {@link DeploymentRepository} against PostgreSQL
 * to prove they select the same denominator and numerator as the engine.
 */
class DeploymentRepositoryChangeFailureRateTest extends PartCIntegrationTestSupport {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-09-08T00:00:00Z");

    @Autowired
    private DeploymentRepository deploymentRepository;

    private UUID workspaceId;
    private UUID repositoryId;
    private UUID otherRepositoryId;

    @BeforeEach
    void createTenant() {
        UUID userId = jdbc.queryForObject("""
            INSERT INTO users (email, password_hash, display_name)
            VALUES ('manager@example.com', 'test-password-hash', 'Test User')
            RETURNING id
            """, UUID.class);
        workspaceId = jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone)
            VALUES ('Test Workspace', 'test-workspace', 'UTC')
            RETURNING id
            """, UUID.class);
        UUID membershipId = jdbc.queryForObject("""
            INSERT INTO memberships (workspace_id, user_id, role, status)
            VALUES (?, ?, 'MANAGER', 'ACTIVE')
            RETURNING id
            """, UUID.class, workspaceId, userId);
        UUID githubIntegrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, 1001, 2001, 'acme', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, membershipId);
        repositoryId = insertRepository(githubIntegrationId, 3001, "engine");
        otherRepositoryId = insertRepository(githubIntegrationId, 3002, "web");
    }

    @Test
    void listsEveryFinishedProductionDeploymentInsideTheWindowNewestFirst() {
        UUID success = insertDeployment(repositoryId, "d-ok", "production", true, "SUCCESS", "good001",
            "2026-09-02T10:00:00Z");
        UUID failure = insertDeployment(repositoryId, "d-fail", "production", true, "FAILURE", "bad0002",
            "2026-09-03T10:00:00Z");
        UUID cancelled = insertDeployment(repositoryId, "d-cancel", "production", true, "CANCELLED", "cncl003",
            "2026-09-04T10:00:00Z");
        UUID successWithIncident = insertDeployment(repositoryId, "d-inc", "production", true, "SUCCESS", "inc0004",
            "2026-09-05T10:00:00Z");
        UUID incident = insertIncident(repositoryId, "Checkout 500s", "SEV1", "2026-09-05T11:00:00Z", successWithIncident);

        // Not production.
        insertDeployment(repositoryId, "d-staging", "staging", false, "FAILURE", "stg0005", "2026-09-03T12:00:00Z");
        // Not finished yet.
        insertDeployment(repositoryId, "d-running", "production", true, "IN_PROGRESS", "run0006", null);
        // Finished exactly at the exclusive upper bound and just before the window.
        insertDeployment(repositoryId, "d-edge", "production", true, "FAILURE", "edge007", "2026-09-08T00:00:00Z");
        insertDeployment(repositoryId, "d-old", "production", true, "FAILURE", "old0008", "2026-08-31T23:59:59Z");
        // In range but in a repository outside the caller's scope.
        insertDeployment(otherRepositoryId, "d-other", "production", true, "FAILURE", "oth0009", "2026-09-04T00:00:00Z");

        Page<ChangeFailureRateRow> page = deploymentRepository.findChangeFailureRateRows(
            List.of(repositoryId), FROM, TO, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(ChangeFailureRateRow::getDeploymentId)
            .containsExactly(successWithIncident, cancelled, failure, success);

        ChangeFailureRateRow linkedRow = page.getContent().get(0);
        assertThat(linkedRow.getStatus()).isEqualTo("SUCCESS");
        assertThat(linkedRow.getEnvironment()).isEqualTo("production");
        assertThat(linkedRow.getCommitSha()).isEqualTo("inc0004");
        assertThat(linkedRow.getFinishedAt()).isEqualTo(Instant.parse("2026-09-05T10:00:00Z"));
        assertThat(linkedRow.getRepositoryName()).isEqualTo("engine");
        assertThat(linkedRow.getIncidentId()).isEqualTo(incident);
        assertThat(linkedRow.getIncidentTitle()).isEqualTo("Checkout 500s");
        assertThat(linkedRow.getIncidentSeverity()).isEqualTo("SEV1");

        ChangeFailureRateRow failureRow = page.getContent().get(2);
        assertThat(failureRow.getStatus()).isEqualTo("FAILURE");
        assertThat(failureRow.getIncidentId()).isNull();

        // Engine parity: FAILURE status or a linked incident counts toward the numerator.
        assertThat(deploymentRepository.countFailedProductionDeployments(List.of(repositoryId), FROM, TO))
            .isEqualTo(2);
    }

    @Test
    void countsFailedDeploymentWithLinkedIncidentOnce() {
        UUID deployment = insertDeployment(repositoryId, "d-fail", "production", true, "FAILURE", "bad0001",
            "2026-09-02T10:00:00Z");
        UUID incident = insertIncident(repositoryId, "Escalated outage", "SEV2", "2026-09-02T11:00:00Z", deployment);
        insertDeployment(repositoryId, "d-ok", "production", true, "SUCCESS", "good002", "2026-09-03T10:00:00Z");

        Page<ChangeFailureRateRow> page = deploymentRepository.findChangeFailureRateRows(
            List.of(repositoryId), FROM, TO, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(1).getDeploymentId()).isEqualTo(deployment);
        assertThat(page.getContent().get(1).getIncidentId()).isEqualTo(incident);
        assertThat(page.getContent().get(0).getIncidentId()).isNull();
        assertThat(deploymentRepository.countFailedProductionDeployments(List.of(repositoryId), FROM, TO))
            .isEqualTo(1);
    }

    @Test
    void paginatesWithAccurateTotals() {
        for (int i = 0; i < 5; i++) {
            insertDeployment(repositoryId, "d-" + i, "production", true, i % 2 == 0 ? "SUCCESS" : "FAILURE",
                "sha000" + i, "2026-09-02T0" + i + ":00:00Z");
        }

        Page<ChangeFailureRateRow> second = deploymentRepository.findChangeFailureRateRows(
            List.of(repositoryId), FROM, TO, PageRequest.of(1, 2));

        assertThat(second.getTotalElements()).isEqualTo(5);
        assertThat(second.getTotalPages()).isEqualTo(3);
        assertThat(second.getContent()).extracting(ChangeFailureRateRow::getCommitSha)
            .containsExactly("sha0002", "sha0001");
        assertThat(deploymentRepository.countFailedProductionDeployments(List.of(repositoryId), FROM, TO))
            .isEqualTo(2);
    }

    private UUID insertRepository(UUID githubIntegrationId, long githubRepoId, String name) {
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled
            ) VALUES (?, ?, ?, 'acme', ?, ?, 'main', 'PRIVATE', true)
            RETURNING id
            """, UUID.class, workspaceId, githubIntegrationId, githubRepoId, name, "acme/" + name);
    }

    private UUID insertDeployment(
            UUID repoId,
            String externalId,
            String environment,
            boolean production,
            String status,
            String sha,
            String finishedAt) {
        return jdbc.queryForObject("""
            INSERT INTO deployments (
                workspace_id, repository_id, source, external_deployment_id,
                environment, is_production, status, commit_sha, finished_at
            ) VALUES (?, ?, 'GITHUB_DEPLOYMENT', ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, UUID.class, workspaceId, repoId, externalId, environment, production, status, sha, ts(finishedAt));
    }

    private UUID insertIncident(UUID repoId, String title, String severity, String detectedAt, UUID failedDeploymentId) {
        return jdbc.queryForObject("""
            INSERT INTO incidents (
                workspace_id, repository_id, source, title, severity, status,
                failed_deployment_id, detected_at
            ) VALUES (?, ?, 'GITHUB', ?, ?, 'OPEN', ?, ?)
            RETURNING id
            """, UUID.class, workspaceId, repoId, title, severity, failedDeploymentId, ts(detectedAt));
    }

    private static Timestamp ts(String instant) {
        return instant == null ? null : Timestamp.from(Instant.parse(instant));
    }
}
