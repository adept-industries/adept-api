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
class V15MigrationUpgradeTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
        .withDatabaseName("adept_v15_upgrade_test").withUsername("adept").withPassword("adept");

    @Test
    void v15AddsGithubIssuesWithoutChangingExistingProviderData() {
        assertThat(flyway().target("14").load().migrate().migrationsExecuted).isEqualTo(14);
        JdbcTemplate jdbc = jdbc();
        UUID workspaceId = jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone) VALUES ('V15', 'v15', 'UTC') RETURNING id
            """, UUID.class);
        UUID githubId = jdbc.queryForObject("""
            INSERT INTO github_integrations (workspace_id, installation_id, account_external_id,
                account_login, account_type, repository_selection, status)
            VALUES (?, 15001, 15002, 'v15', 'ORGANIZATION', 'ALL', 'ACTIVE') RETURNING id
            """, UUID.class, workspaceId);
        UUID repositoryId = jdbc.queryForObject("""
            INSERT INTO repositories (workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, settings)
            VALUES (?, ?, 15003, 'v15', 'api', 'v15/api', 'main', 'PRIVATE', true, '{}'::jsonb) RETURNING id
            """, UUID.class, workspaceId, githubId);

        assertThat(flyway().target("15").load().migrate().migrationsExecuted).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM repositories WHERE id = ?", Integer.class, repositoryId)).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.github_issues')::text", String.class)).isEqualTo("github_issues");
        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.idx_github_issues_repo_open_updated') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.idx_jira_issues_project_open_updated') IS NOT NULL", Boolean.class)).isTrue();
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
