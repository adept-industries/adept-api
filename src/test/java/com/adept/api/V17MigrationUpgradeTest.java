package com.adept.api;

import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class V17MigrationUpgradeTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
        .withDatabaseName("adept_v17_upgrade_test").withUsername("adept").withPassword("adept");

    @Test
    void v17AddsReviewCycleTimeStorageWithoutChangingExistingMetrics() {
        assertThat(flyway().target("16").load().migrate().migrationsExecuted).isEqualTo(16);
        JdbcTemplate jdbc = jdbc();
        UUID workspaceId = jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone) VALUES ('V17', 'v17', 'UTC') RETURNING id
            """, UUID.class);
        UUID githubId = jdbc.queryForObject("""
            INSERT INTO github_integrations (workspace_id, installation_id, account_external_id,
                account_login, account_type, repository_selection, status)
            VALUES (?, 17001, 17002, 'v17', 'ORGANIZATION', 'ALL', 'ACTIVE') RETURNING id
            """, UUID.class, workspaceId);
        UUID repositoryId = jdbc.queryForObject("""
            INSERT INTO repositories (workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, settings)
            VALUES (?, ?, 17003, 'v17', 'api', 'v17/api', 'main', 'PRIVATE', true, '{}'::jsonb) RETURNING id
            """, UUID.class, workspaceId, githubId);
        UUID pullRequestId = jdbc.queryForObject("""
            INSERT INTO pull_requests (workspace_id, repository_id, github_pr_id, number, title, state,
                base_ref, head_ref, opened_at)
            VALUES (?, ?, 17004, 1, 'Add review metrics', 'OPEN', 'main', 'feature', now()) RETURNING id
            """, UUID.class, workspaceId, repositoryId);
        jdbc.update("""
            INSERT INTO metric_snapshots (workspace_id, repository_id, metric_type, granularity,
                period_start, period_end, value, unit, calculation_version)
            VALUES (?, ?, 'CHANGE_LEAD_TIME_HOURS', 'DAY', '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z',
                12, 'hours', 'dora-v3')
            """, workspaceId, repositoryId);

        assertThat(flyway().target("17").load().migrate().migrationsExecuted).isOne();

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM metric_snapshots WHERE metric_type = 'CHANGE_LEAD_TIME_HOURS'",
            Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT ready_for_review_at FROM pull_requests WHERE id = ?", Instant.class, pullRequestId)).isNull();

        jdbc.update("""
            INSERT INTO pull_request_reviews (pull_request_id, github_review_id, reviewer_login, state, submitted_at)
            VALUES (?, 17005, 'reviewer', 'CHANGES_REQUESTED', now())
            """, pullRequestId);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM pull_request_reviews WHERE pull_request_id = ?", Integer.class, pullRequestId))
            .isOne();
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO pull_request_reviews (pull_request_id, github_review_id, state, submitted_at)
            VALUES (?, 17006, 'PENDING', now())
            """, pullRequestId)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
            INSERT INTO metric_snapshots (workspace_id, repository_id, metric_type, granularity,
                period_start, period_end, value, unit, calculation_version)
            VALUES (?, ?, 'PR_PICKUP_TIME_HOURS', 'DAY', '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z',
                4, 'hours', 'cycle-time-v1')
            """, workspaceId, repositoryId);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO metric_snapshots (workspace_id, repository_id, metric_type, granularity,
                period_start, period_end, value, unit, calculation_version)
            VALUES (?, ?, 'UNKNOWN_METRIC', 'DAY', '2026-09-01T00:00:00Z', '2026-09-02T00:00:00Z',
                1, 'hours', 'cycle-time-v1')
            """, workspaceId, repositoryId)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM pull_requests WHERE id = ?", pullRequestId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pull_request_reviews", Integer.class)).isZero();
    }

    private FluentConfiguration flyway() {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").validateMigrationNaming(true);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
}
