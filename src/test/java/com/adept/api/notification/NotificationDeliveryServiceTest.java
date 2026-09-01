package com.adept.api.notification;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.adept.api.alert.AlertRule;
import com.adept.api.alert.AlertRuleRepository;
import com.adept.api.auth.PartCIntegrationTestSupport;
import com.adept.api.common.domain.AlertComparator;
import com.adept.api.common.domain.NotificationChannel;
import com.adept.api.common.domain.NotificationStatus;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.github.GitRepositoryRepository;
import com.adept.api.workspace.Workspace;
import com.adept.api.workspace.WorkspaceRepository;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeliveryServiceTest extends PartCIntegrationTestSupport {

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private GitRepositoryRepository gitRepositoryRepository;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @BeforeEach
    void setUp() {
        mailSender.reset();
    }

    @Test
    void processesPendingDeliveriesAndSendsEmails() {
        TestContext ctx = setupTestEntities("notify-test-1");

        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setWorkspace(ctx.workspace());
        delivery.setRepository(ctx.repository());
        delivery.setAlertRule(ctx.alertRule());
        delivery.setEventKey("rule-1:snapshot-1");
        delivery.setChannel(NotificationChannel.EMAIL);
        delivery.setDestination("lead-alerts@example.com");
        delivery.setStatus(NotificationStatus.PENDING);
        delivery.setPayload(Map.of(
            "subject", "[Adept Alert] CFR Alert Triggered",
            "text", "Condition: GT 15.0. Actual Value: 25.0",
            "rule_id", ctx.alertRule().getId().toString()
        ));
        deliveryRepository.save(delivery);

        int processed = deliveryService.processBatch();
        assertThat(processed).isEqualTo(1);

        CapturedMail mail = mailSender.await(
            m -> m.recipients().contains("lead-alerts@example.com"),
            java.time.Duration.ofSeconds(5)
        );
        assertThat(mail.subject()).isEqualTo("[Adept Alert] CFR Alert Triggered");
        assertThat(mail.body()).contains("Condition: GT 15.0");

        NotificationDelivery updated = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(updated.getAttempts()).isEqualTo(1);
        assertThat(updated.getSentAt()).isNotNull();
        assertThat(updated.getLastError()).isNull();
    }

    @Test
    void marksFailedAndIncrementsAttemptsOnMailFailure() {
        TestContext ctx = setupTestEntities("notify-fail-test");

        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setWorkspace(ctx.workspace());
        delivery.setRepository(ctx.repository());
        delivery.setAlertRule(ctx.alertRule());
        delivery.setEventKey("rule-fail:snapshot-2");
        delivery.setChannel(NotificationChannel.EMAIL);
        delivery.setDestination("lead-fail@example.com");
        delivery.setStatus(NotificationStatus.PENDING);
        delivery.setPayload(Map.of(
            "subject", "[Adept Alert] Failing Alert",
            "text", "Alert text"
        ));
        deliveryRepository.save(delivery);

        mailSender.failSending(true);

        int processed = deliveryService.processBatch();
        assertThat(processed).isEqualTo(1);

        NotificationDelivery failed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("simulated SMTP failure");

        // A failed delivery is not retried on every scheduler poll.
        assertThat(deliveryService.processBatch()).isZero();
        NotificationDelivery stillFailed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(stillFailed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(stillFailed.getAttempts()).isEqualTo(1);

        // Once its retry delay has elapsed, the fifth attempt becomes terminal on failure.
        jdbc.update(
            "UPDATE notification_deliveries "
                + "SET attempts = 4, updated_at = now() - interval '31 seconds' WHERE id = ?",
            delivery.getId()
        );
        assertThat(deliveryService.processBatch()).isEqualTo(1);

        NotificationDelivery dead = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(NotificationStatus.DEAD);
        assertThat(dead.getAttempts()).isEqualTo(5);
    }

    @Test
    void onlyRecoversSendingDeliveriesAfterTheirLeaseExpires() {
        TestContext ctx = setupTestEntities("notify-lease-test");

        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setWorkspace(ctx.workspace());
        delivery.setRepository(ctx.repository());
        delivery.setAlertRule(ctx.alertRule());
        delivery.setEventKey("evaluation:" + UUID.randomUUID());
        delivery.setChannel(NotificationChannel.EMAIL);
        delivery.setDestination("lead-lease@example.com");
        delivery.setStatus(NotificationStatus.PENDING);
        delivery.setPayload(Map.of(
            "subject", "[Adept Alert] Lease Alert",
            "text", "Lease recovery alert"
        ));
        delivery = deliveryRepository.saveAndFlush(delivery);

        jdbc.update(
            "UPDATE notification_deliveries SET status = 'SENDING', attempts = 1, updated_at = now() WHERE id = ?",
            delivery.getId()
        );
        assertThat(deliveryService.processBatch()).isZero();
        assertThat(mailSender.messages()).isEmpty();

        jdbc.update(
            "UPDATE notification_deliveries SET updated_at = now() - interval '301 seconds' WHERE id = ?",
            delivery.getId()
        );
        assertThat(deliveryService.processBatch()).isEqualTo(1);

        NotificationDelivery recovered = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(recovered.getAttempts()).isEqualTo(2);
        assertThat(recovered.getSentAt()).isNotNull();
    }

    private TestContext setupTestEntities(String prefix) {
        String email = uniqueEmail(prefix);
        UUID userId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits());

        jdbc.update("""
            INSERT INTO users (
                id, email, password_hash, display_name, status, email_verified_at,
                token_version, created_at, updated_at, version
            ) VALUES (?, ?, 'unused-test-hash', 'Alert Test User', 'ACTIVE', now(), 0, now(), now(), 0)
            """, userId, email);

        jdbc.update("""
            INSERT INTO workspaces (
                id, name, slug, timezone, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'UTC', now(), now(), 0)
            """, workspaceId, "Alert WS " + prefix, "slug-" + prefix);

        jdbc.update("""
            INSERT INTO memberships (
                id, workspace_id, user_id, role, status, joined_at, created_at, updated_at, version
            ) VALUES (?, ?, ?, 'MANAGER', 'ACTIVE', now(), now(), now(), 0)
            """, membershipId, workspaceId, userId);

        jdbc.update("""
            INSERT INTO github_integrations (
                id, workspace_id, installation_id, account_external_id, account_login,
                account_type, repository_selection, status, installed_by_membership_id,
                created_at, updated_at, version
            ) VALUES (?, ?, ?, ?, 'adept-test', 'ORGANIZATION', 'ALL', 'ACTIVE', ?, now(), now(), 0)
            """, integrationId, workspaceId, suffix, suffix + 1, membershipId);

        jdbc.update("""
            INSERT INTO repositories (
                id, workspace_id, github_integration_id, github_repo_id, owner_login,
                name, full_name, default_branch, visibility, tracking_enabled, archived,
                created_at, updated_at, version
            ) VALUES (?, ?, ?, ?, 'adept-test', ?, ?, 'main', 'PRIVATE', true, false, now(), now(), 0)
            """, repositoryId, workspaceId, integrationId, suffix + 2, "repo-" + prefix, "adept-test/repo-" + prefix);

        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
        GitRepository repository = gitRepositoryRepository.findById(repositoryId).orElseThrow();

        AlertRule rule = new AlertRule();
        rule.setWorkspace(workspace);
        rule.setRepository(repository);
        rule.setName("Rule " + prefix);
        rule.setMetricType(com.adept.api.common.domain.AlertMetricType.CHANGE_FAILURE_RATE_PERCENT);
        rule.setComparator(AlertComparator.GT);
        rule.setThresholdValue(new java.math.BigDecimal("15.0"));
        rule.setEvaluationWindowMinutes(1440);
        rule.setCooldownMinutes(60);
        rule.setChannel(NotificationChannel.EMAIL);
        rule.setDestination(prefix + "@example.com");
        rule.setEnabled(true);
        rule = alertRuleRepository.save(rule);

        return new TestContext(workspace, repository, rule);
    }

    private record TestContext(
        Workspace workspace,
        GitRepository repository,
        AlertRule alertRule
    ) {}
}
