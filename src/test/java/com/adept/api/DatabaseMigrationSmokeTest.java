package com.adept.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

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
class DatabaseMigrationSmokeTest {

    @Container
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("adept_test")
            .withUsername("adept")
            .withPassword("adept");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAndHibernateValidatesFlywaySchema() {
        assertThat(flyway.info().applied()).hasSize(7);

        Integer serverVersion = jdbc.queryForObject(
            "SELECT current_setting('server_version_num')::integer", Integer.class);
        assertThat(serverVersion).isNotNull();
        assertThat(serverVersion / 10_000).isEqualTo(18);

        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.users')::text", String.class)).isEqualTo("users");
        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.audit_logs')::text", String.class)).isEqualTo("audit_logs");
        assertThat(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'repository_lead_assignments'
                  AND column_name = 'created_at'
            )
            """, Boolean.class)).isTrue();
    }
}
