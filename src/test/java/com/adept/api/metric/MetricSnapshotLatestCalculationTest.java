package com.adept.api.metric;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.adept.api.common.domain.MetricGranularity;

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
class MetricSnapshotLatestCalculationTest {

    @Container
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("adept_latest_calculation_test")
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

    @Autowired
    MetricSnapshotRepository metricSnapshotRepository;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE TABLE workspaces, users CASCADE");
    }

    @Test
    void latestCalculationIsPerRepositoryAcrossAllPeriodsOfTheVersion() {
        UUID workspaceId = jdbc.queryForObject("""
            INSERT INTO workspaces (name, slug, timezone) VALUES ('Latest', 'latest', 'UTC') RETURNING id
            """, UUID.class);
        UUID integrationId = jdbc.queryForObject("""
            INSERT INTO github_integrations (workspace_id, installation_id, account_external_id,
                account_login, account_type, repository_selection, status)
            VALUES (?, 1, 2, 'adept', 'ORGANIZATION', 'ALL', 'ACTIVE') RETURNING id
            """, UUID.class, workspaceId);
        UUID busy = repository(workspaceId, integrationId, 11, "busy");
        UUID quiet = repository(workspaceId, integrationId, 12, "quiet");
        UUID neverCalculated = repository(workspaceId, integrationId, 13, "new");

        Instant busyLatest = Instant.parse("2026-09-30T08:00:00Z");
        Instant quietLatest = Instant.parse("2026-09-01T08:00:00Z");
        snapshot(workspaceId, busy, "DAY", "cycle-time-v2", "2026-09-29T00:00:00Z", busyLatest);
        snapshot(workspaceId, busy, "DAY", "cycle-time-v2", "2026-09-10T00:00:00Z", busyLatest.minusSeconds(86_400));
        // Only the quiet repository's old periods were ever recalculated.
        snapshot(workspaceId, quiet, "DAY", "cycle-time-v2", "2026-08-20T00:00:00Z", quietLatest);
        // Other versions and granularities do not count as cycle-time calculations.
        snapshot(workspaceId, neverCalculated, "DAY", "dora-v3", "2026-09-29T00:00:00Z", busyLatest);
        snapshot(workspaceId, neverCalculated, "WEEK", "cycle-time-v2", "2026-09-28T00:00:00Z", busyLatest);

        Map<UUID, Instant> latest = metricSnapshotRepository.findLatestCalculations(
                workspaceId, List.of(busy, quiet, neverCalculated), MetricGranularity.DAY, "cycle-time-v2")
            .stream()
            .collect(Collectors.toMap(
                MetricSnapshotRepository.LatestCalculation::getRepositoryId,
                MetricSnapshotRepository.LatestCalculation::getCalculatedAt));

        assertThat(latest).containsOnly(Map.entry(busy, busyLatest), Map.entry(quiet, quietLatest));
    }

    private UUID repository(UUID workspaceId, UUID integrationId, long githubRepoId, String name) {
        return jdbc.queryForObject("""
            INSERT INTO repositories (workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, settings)
            VALUES (?, ?, ?, 'adept', ?, ?, 'main', 'PRIVATE', true, '{}'::jsonb) RETURNING id
            """, UUID.class, workspaceId, integrationId, githubRepoId, name, "adept/" + name);
    }

    private void snapshot(
            UUID workspaceId,
            UUID repositoryId,
            String granularity,
            String version,
            String periodStart,
            Instant calculatedAt) {
        Instant start = Instant.parse(periodStart);
        jdbc.update("""
            INSERT INTO metric_snapshots (workspace_id, repository_id, metric_type, granularity,
                period_start, period_end, value, unit, calculation_version, calculated_at)
            VALUES (?, ?, 'PR_CODING_TIME_HOURS', ?, ?, ?, 0, 'hours', ?, ?)
            """,
            workspaceId, repositoryId, granularity, Timestamp.from(start),
            Timestamp.from(start.plusSeconds(86_400)), version, Timestamp.from(calculatedAt));
    }
}
