package com.adept.api;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class V13MigrationUpgradeTest {

    @Container
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("adept_v13_upgrade_test")
            .withUsername("adept")
            .withPassword("adept");

    @Test
    void v13UpgradesExistingMetricDataWithoutUniquenessFailures() {
        Flyway throughV12 = flyway().target("12").load();
        assertThat(throughV12.migrate().migrationsExecuted).isEqualTo(12);

        JdbcTemplate jdbc = jdbc();
        UUID workspaceId = jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone)
            VALUES ('V13 Upgrade Test', 'v13-upgrade-test', 'UTC')
            RETURNING id
            """, UUID.class);
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status
            ) VALUES (?, 13001, 13002, 'adept-v13-test', 'ORGANIZATION', 'ALL', 'ACTIVE')
            RETURNING id
            """, UUID.class, workspaceId);
        UUID repositoryId = jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled,
                settings
            ) VALUES (
                ?, ?, 13003, 'adept-v13-test', 'api', 'adept-v13-test/api',
                'main', 'PRIVATE', true, '{"deploymentSignal":"WORKFLOW_RUN"}'::jsonb
            )
            RETURNING id
            """, UUID.class, workspaceId, integrationId);
        UUID deploymentId = jdbc.queryForObject("""
            INSERT INTO deployments (
                workspace_id, repository_id, source, external_deployment_id,
                environment, is_production, status, commit_sha, finished_at
            ) VALUES (?, ?, 'GITHUB_WORKFLOW', 'v13-failure', 'production', true,
                      'FAILURE', 'failure-sha', now())
            RETURNING id
            """, UUID.class, workspaceId, repositoryId);

        jdbc.update("""
            INSERT INTO incidents (
                workspace_id, repository_id, source, title, status,
                failed_deployment_id, detected_at
            ) VALUES
                (?, ?, 'GITHUB', 'First duplicate', 'OPEN', ?, now() - interval '1 minute'),
                (?, ?, 'MANUAL', 'Second duplicate', 'OPEN', ?, now())
            """,
            workspaceId, repositoryId, deploymentId,
            workspaceId, repositoryId, deploymentId);
        jdbc.update("""
            INSERT INTO processing_jobs (
                workspace_id, repository_id, job_type, payload, status, created_at
            ) VALUES
                (?, ?, 'RECALCULATE_METRICS', '{}'::jsonb, 'PENDING', now() - interval '1 minute'),
                (?, ?, 'RECALCULATE_METRICS', '{}'::jsonb, 'FAILED', now())
            """,
            workspaceId, repositoryId,
            workspaceId, repositoryId);

        Flyway throughV13 = flyway().target("13").load();
        assertThat(throughV13.migrate().migrationsExecuted).isOne();
        assertThat(throughV13.info().applied()).hasSize(13);

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM incidents WHERE failed_deployment_id = ?
            """, Integer.class, deploymentId)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM processing_jobs
            WHERE repository_id = ?
              AND job_type = 'RECALCULATE_METRICS'
              AND status IN ('PENDING', 'FAILED')
            """, Integer.class, repositoryId)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM processing_jobs
            WHERE repository_id = ?
              AND job_type = 'RECALCULATE_METRICS'
              AND status = 'DEAD'
            """, Integer.class, repositoryId)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT jsonb_exists_all(settings, ARRAY[
                'productionBranchPatterns',
                'productionEnvironmentPatterns',
                'deploymentWorkflowNamePatterns',
                'incidentSource',
                'doraExclusions'
            ])
            FROM repositories
            WHERE id = ?
            """, Boolean.class, repositoryId)).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.pull_request_commits')::text",
            String.class
        )).isEqualTo("pull_request_commits");
    }

    private FluentConfiguration flyway() {
        return Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .validateMigrationNaming(true);
    }

    private JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
        return new JdbcTemplate(dataSource);
    }
}
