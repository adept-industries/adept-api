package com.adept.api.incident;

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
 * Runs {@link IncidentRepository#findRecoveryTimeRows} against PostgreSQL to prove it
 * selects exactly the incidents the engine counts toward recovery time.
 */
class IncidentRepositoryRecoveryTimeTest extends PartCIntegrationTestSupport {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-09-08T00:00:00Z");

    @Autowired
    private IncidentRepository incidentRepository;

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
    void returnsOnlyResolvedIncidentsResolvedInsideTheWindowNewestFirst() {
        UUID failed = insertDeployment(repositoryId, "d-fail", "FAILURE", "bad0001", "2026-09-02T10:00:00Z");
        UUID recovery = insertDeployment(repositoryId, "d-fix", "SUCCESS", "good002", "2026-09-02T12:30:00Z");

        UUID linked = insertIncident(repositoryId, "Checkout 500s", "GITHUB", "SEV1", "RESOLVED",
            "2026-09-02T10:05:00Z", "2026-09-02T13:00:00Z", failed, recovery);
        UUID manual = insertIncident(repositoryId, "Queue backlog", "MANUAL", "UNKNOWN", "RESOLVED",
            "2026-09-05T08:00:00Z", "2026-09-05T08:45:00Z", null, null);

        // Open incident detected inside the window: must never appear.
        insertIncident(repositoryId, "Still broken", "GITHUB", "SEV2", "OPEN",
            "2026-09-03T00:00:00Z", null, null, null);
        // Resolved exactly at the exclusive upper bound.
        insertIncident(repositoryId, "Edge of window", "JIRA", "SEV3", "RESOLVED",
            "2026-09-07T20:00:00Z", "2026-09-08T00:00:00Z", null, null);
        // Detected inside, resolved before the window start.
        insertIncident(repositoryId, "Old incident", "MANUAL", "SEV4", "RESOLVED",
            "2026-08-30T00:00:00Z", "2026-08-31T23:59:59Z", null, null);
        // Resolved in range but in a repository outside the caller's scope.
        insertIncident(otherRepositoryId, "Other repo", "MANUAL", "SEV1", "RESOLVED",
            "2026-09-04T00:00:00Z", "2026-09-04T01:00:00Z", null, null);

        Page<RecoveryTimeRow> page = incidentRepository.findRecoveryTimeRows(
            List.of(repositoryId), FROM, TO, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(RecoveryTimeRow::getIncidentId)
            .containsExactly(manual, linked);

        RecoveryTimeRow linkedRow = page.getContent().get(1);
        assertThat(linkedRow.getSource()).isEqualTo("GITHUB");
        assertThat(linkedRow.getSeverity()).isEqualTo("SEV1");
        assertThat(linkedRow.getRepositoryName()).isEqualTo("engine");
        // Engine parity: the recovery deployment finish time wins over resolved_at.
        assertThat(linkedRow.getEffectiveResolvedAt()).isEqualTo(Instant.parse("2026-09-02T12:30:00Z"));
        assertThat(linkedRow.getResolvedAt()).isEqualTo(Instant.parse("2026-09-02T13:00:00Z"));
        assertThat(linkedRow.getFailedDeploymentId()).isEqualTo(failed);
        assertThat(linkedRow.getFailedDeploymentCommitSha()).isEqualTo("bad0001");
        assertThat(linkedRow.getRecoveryDeploymentId()).isEqualTo(recovery);
        assertThat(linkedRow.getRecoveryDeploymentEnvironment()).isEqualTo("production");

        RecoveryTimeRow manualRow = page.getContent().get(0);
        assertThat(manualRow.getEffectiveResolvedAt()).isEqualTo(Instant.parse("2026-09-05T08:45:00Z"));
        assertThat(manualRow.getFailedDeploymentId()).isNull();
        assertThat(manualRow.getRecoveryDeploymentId()).isNull();
    }

    @Test
    void usesRecoveryDeploymentTimeForTheWindowAndSkipsNegativeDurations() {
        // resolved_at is inside the window, but the linked recovery finished before it opened.
        UUID earlyRecovery = insertDeployment(repositoryId, "d-early", "SUCCESS", "early01", "2026-08-31T22:00:00Z");
        insertIncident(repositoryId, "Recovered before window", "GITHUB", "SEV2", "RESOLVED",
            "2026-08-31T20:00:00Z", "2026-09-01T02:00:00Z", null, earlyRecovery);

        // Recovery deployment finished before detection: the engine drops negative durations.
        UUID beforeDetection = insertDeployment(repositoryId, "d-before", "SUCCESS", "before1", "2026-09-03T09:00:00Z");
        insertIncident(repositoryId, "Negative duration", "GITHUB", "SEV2", "RESOLVED",
            "2026-09-03T10:00:00Z", "2026-09-03T11:00:00Z", null, beforeDetection);

        Page<RecoveryTimeRow> page = incidentRepository.findRecoveryTimeRows(
            List.of(repositoryId), FROM, TO, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void paginatesWithAccurateTotals() {
        for (int i = 0; i < 5; i++) {
            insertIncident(repositoryId, "Incident " + i, "MANUAL", "UNKNOWN", "RESOLVED",
                "2026-09-02T0" + i + ":00:00Z", "2026-09-02T0" + i + ":30:00Z", null, null);
        }

        Page<RecoveryTimeRow> second = incidentRepository.findRecoveryTimeRows(
            List.of(repositoryId), FROM, TO, PageRequest.of(1, 2));

        assertThat(second.getTotalElements()).isEqualTo(5);
        assertThat(second.getTotalPages()).isEqualTo(3);
        assertThat(second.getContent()).extracting(RecoveryTimeRow::getTitle)
            .containsExactly("Incident 2", "Incident 1");
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

    private UUID insertDeployment(UUID repoId, String externalId, String status, String sha, String finishedAt) {
        return jdbc.queryForObject("""
            INSERT INTO deployments (
                workspace_id, repository_id, source, external_deployment_id,
                environment, is_production, status, commit_sha, finished_at
            ) VALUES (?, ?, 'GITHUB_DEPLOYMENT', ?, 'production', true, ?, ?, ?)
            RETURNING id
            """, UUID.class, workspaceId, repoId, externalId, status, sha, ts(finishedAt));
    }

    private UUID insertIncident(
            UUID repoId,
            String title,
            String source,
            String severity,
            String status,
            String detectedAt,
            String resolvedAt,
            UUID failedDeploymentId,
            UUID recoveryDeploymentId) {
        return jdbc.queryForObject("""
            INSERT INTO incidents (
                workspace_id, repository_id, source, title, severity, status,
                failed_deployment_id, recovery_deployment_id, detected_at, resolved_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, UUID.class, workspaceId, repoId, source, title, severity, status,
            failedDeploymentId, recoveryDeploymentId, ts(detectedAt), ts(resolvedAt));
    }

    private static Timestamp ts(String instant) {
        return instant == null ? null : Timestamp.from(Instant.parse(instant));
    }
}
