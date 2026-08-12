package com.adept.api;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class V8MigrationUpgradeTest {

    @Container
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("adept_v8_upgrade_test")
            .withUsername("adept")
            .withPassword("adept");

    @Test
    void v8PreservesExistingAssignmentAndAllowsAnotherLead() {
        Flyway throughV7 = flyway().target("7").load();
        assertThat(throughV7.migrate().migrationsExecuted).isEqualTo(7);

        JdbcTemplate jdbc = jdbc();
        UUID workspaceId = insertWorkspace(jdbc);
        UUID managerMembershipId =
            insertMembership(jdbc, workspaceId, "manager@example.com", "MANAGER");
        UUID leadMembershipId =
            insertMembership(jdbc, workspaceId, "lead@example.com", "LEAD");
        UUID repositoryId = insertRepository(jdbc, workspaceId, managerMembershipId);
        UUID originalAssignmentId = insertAssignment(
            jdbc,
            workspaceId,
            repositoryId,
            leadMembershipId,
            managerMembershipId
        );

        Flyway throughV8 = flyway().target("8").load();
        assertThat(throughV8.migrate().migrationsExecuted).isOne();
        assertThat(throughV8.info().applied()).hasSize(8);

        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM repository_lead_assignments
            WHERE id = ?
              AND workspace_id = ?
              AND repository_id = ?
              AND lead_membership_id = ?
              AND assigned_by_membership_id = ?
            """, Integer.class,
            originalAssignmentId,
            workspaceId,
            repositoryId,
            leadMembershipId,
            managerMembershipId
        )).isEqualTo(1);

        UUID coLeadMembershipId =
            insertMembership(jdbc, workspaceId, "co-lead@example.com", "LEAD");
        UUID coLeadAssignmentId = insertAssignment(
            jdbc,
            workspaceId,
            repositoryId,
            coLeadMembershipId,
            managerMembershipId
        );

        assertThat(coLeadAssignmentId).isNotEqualTo(originalAssignmentId);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM repository_lead_assignments
            WHERE repository_id = ?
            """, Integer.class, repositoryId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_constraint
            WHERE conname IN (
                'uq_repository_lead_assignment_membership',
                'uq_repository_lead_assignment_invitation'
            )
            """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
            FROM pg_constraint
            WHERE conname = 'repository_lead_assignments_repository_id_key'
            """, Integer.class)).isZero();
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

    private UUID insertWorkspace(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone)
            VALUES ('V8 Upgrade Test', 'v8-upgrade-test', 'UTC')
            RETURNING id
            """, UUID.class);
    }

    private UUID insertMembership(
        JdbcTemplate jdbc,
        UUID workspaceId,
        String email,
        String role
    ) {
        UUID userId = jdbc.queryForObject("""
            INSERT INTO users (email, password_hash, display_name)
            VALUES (?, 'test-password-hash', 'Test User')
            RETURNING id
            """, UUID.class, email);
        return jdbc.queryForObject("""
            INSERT INTO memberships (workspace_id, user_id, role, status)
            VALUES (?, ?, ?, 'ACTIVE')
            RETURNING id
            """, UUID.class, workspaceId, userId, role);
    }

    private UUID insertRepository(
        JdbcTemplate jdbc,
        UUID workspaceId,
        UUID managerMembershipId
    ) {
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (
                workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id
            ) VALUES (?, 8001, 8002, 'adept-v8-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?)
            RETURNING id
            """, UUID.class, workspaceId, managerMembershipId);
        return jdbc.queryForObject("""
            INSERT INTO repositories (
                workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility
            ) VALUES (
                ?, ?, 8003, 'adept-v8-test', 'api',
                'adept-v8-test/api', 'main', 'PRIVATE'
            )
            RETURNING id
            """, UUID.class, workspaceId, integrationId);
    }

    private UUID insertAssignment(
        JdbcTemplate jdbc,
        UUID workspaceId,
        UUID repositoryId,
        UUID leadMembershipId,
        UUID managerMembershipId
    ) {
        return jdbc.queryForObject("""
            INSERT INTO repository_lead_assignments (
                workspace_id, repository_id, lead_membership_id,
                assigned_by_membership_id
            ) VALUES (?, ?, ?, ?)
            RETURNING id
            """, UUID.class,
            workspaceId,
            repositoryId,
            leadMembershipId,
            managerMembershipId);
    }
}
