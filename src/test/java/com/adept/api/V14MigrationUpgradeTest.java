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
class V14MigrationUpgradeTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
        .withDatabaseName("adept_v14_upgrade_test").withUsername("adept").withPassword("adept");

    @Test
    void v14BackfillsTrackedRepositoryMappingsToTheirAdeptProjects() {
        assertThat(flyway().target("13").load().migrate().migrationsExecuted).isEqualTo(13);
        JdbcTemplate jdbc = jdbc();
        UUID workspaceId = jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone) VALUES ('V14', 'v14', 'UTC') RETURNING id
            """, UUID.class);
        UUID githubId = jdbc.queryForObject("""
            INSERT INTO github_integrations (workspace_id, installation_id, account_external_id,
                account_login, account_type, repository_selection, status)
            VALUES (?, 14001, 14002, 'v14', 'ORGANIZATION', 'ALL', 'ACTIVE') RETURNING id
            """, UUID.class, workspaceId);
        UUID repositoryId = jdbc.queryForObject("""
            INSERT INTO repositories (workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, settings)
            VALUES (?, ?, 14003, 'v14', 'api', 'v14/api', 'main', 'PRIVATE', true, '{}'::jsonb) RETURNING id
            """, UUID.class, workspaceId, githubId);
        UUID projectId = jdbc.queryForObject("""
            INSERT INTO projects (workspace_id, name) VALUES (?, 'Platform') RETURNING id
            """, UUID.class, workspaceId);
        jdbc.update("INSERT INTO project_repositories (project_id, repository_id, workspace_id) VALUES (?, ?, ?)",
            projectId, repositoryId, workspaceId);
        UUID jiraIntegrationId = jdbc.queryForObject("""
            INSERT INTO jira_integrations (workspace_id, cloud_id, site_url, display_name,
                access_token_enc, refresh_token_enc, encryption_key_version, access_token_expires_at, status)
            VALUES (?, 'cloud-v14', 'https://v14.atlassian.net', 'V14 Jira', 'a', 'r', 1, now(), 'ACTIVE') RETURNING id
            """, UUID.class, workspaceId);
        UUID jiraProjectId = jdbc.queryForObject("""
            INSERT INTO jira_projects (workspace_id, jira_integration_id, jira_project_id, project_key,
                project_name, tracking_enabled)
            VALUES (?, ?, '14004', 'V14', 'V14 Project', true) RETURNING id
            """, UUID.class, workspaceId, jiraIntegrationId);
        jdbc.update("INSERT INTO repository_jira_projects (repository_id, jira_project_id) VALUES (?, ?)",
            repositoryId, jiraProjectId);

        assertThat(flyway().target("14").load().migrate().migrationsExecuted).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM project_jira_projects
            WHERE project_id = ? AND jira_project_id = ? AND workspace_id = ?
            """, Integer.class, projectId, jiraProjectId, workspaceId)).isOne();
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
