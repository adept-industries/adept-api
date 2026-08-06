package com.adept.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.adept.api.auth.PartCIntegrationTestSupport;

class AccountMailListenerTest extends PartCIntegrationTestSupport {

    @Autowired
    private AccountMailService accountMailService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("accountMailExecutor")
    private Executor accountMailExecutor;

    @Test
    void mailEventDiagnosticStringsRedactRecipientsAndRawTokens() {
        UUID userId = UUID.randomUUID();
        String recipient = "private-recipient@example.com";
        String rawToken = "private-raw-token";

        assertThat(new VerificationMailRequested(userId, recipient, rawToken, "trace-verify").toString())
            .contains(userId.toString(), "trace-verify", "recipient=<redacted>", "rawToken=<redacted>")
            .doesNotContain(recipient, rawToken);
        assertThat(new PasswordResetMailRequested(userId, recipient, rawToken, "trace-reset").toString())
            .contains(userId.toString(), "trace-reset", "recipient=<redacted>", "rawToken=<redacted>")
            .doesNotContain(recipient, rawToken);
        assertThat(new PasswordChangedMailRequested(userId, recipient, "trace-changed").toString())
            .contains(userId.toString(), "trace-changed", "recipient=<redacted>")
            .doesNotContain(recipient);
    }

    @Test
    void verificationAndResetLinksUseFragmentsOnTheConfiguredFrontendOrigin() {
        String verificationRecipient = uniqueEmail("mail-verify");
        String resetRecipient = uniqueEmail("mail-reset");

        accountMailService.sendVerification(new VerificationMailRequested(
            UUID.randomUUID(), verificationRecipient, "VERIFY_TOKEN", "trace-1"));
        accountMailService.sendPasswordReset(new PasswordResetMailRequested(
            UUID.randomUUID(), resetRecipient, "RESET_TOKEN", "trace-2"));

        var verification = mailSender.messages().stream()
            .filter(message -> message.recipients().contains(verificationRecipient))
            .findFirst().orElseThrow();
        var reset = mailSender.messages().stream()
            .filter(message -> message.recipients().contains(resetRecipient))
            .findFirst().orElseThrow();
        assertThat(verification.body())
            .contains("http://localhost:3000/verify-email#token=VERIFY_TOKEN")
            .doesNotContain("?token=");
        assertThat(reset.body())
            .contains("http://localhost:3000/reset-password#token=RESET_TOKEN")
            .doesNotContain("?token=");
    }

    @Test
    void transactionalMailRunsOnlyAfterCommitAndOnTheNamedExecutor() {
        String recipient = uniqueEmail("mail-after-commit");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new VerificationMailRequested(
                UUID.randomUUID(), recipient, "RAW_TOKEN", "trace-after-commit"));
            assertThat(mailSender.messages()).noneMatch(
                message -> message.recipients().contains(recipient));
        });

        var delivered = mailSender.await(
            message -> message.recipients().contains(recipient),
            Duration.ofSeconds(5)
        );
        assertThat(delivered.threadName()).startsWith("account-mail-");
    }

    @Test
    void rolledBackTransactionDoesNotSendMail() throws Exception {
        String recipient = uniqueEmail("mail-rollback");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new PasswordResetMailRequested(
                UUID.randomUUID(), recipient, "RAW_TOKEN", "trace-rollback"));
            status.setRollbackOnly();
        });

        Thread.sleep(200);
        assertThat(mailSender.messages()).noneMatch(
            message -> message.recipients().contains(recipient));
    }

    @Test
    void saturatedExecutorDiscardsInsteadOfRunningWorkOnTheRequestThread() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) accountMailExecutor;
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        executor.execute(blocker);
        executor.execute(blocker);
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 100; index++) {
            executor.execute(blocker);
        }
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();
        String requestThread = Thread.currentThread().getName();

        executor.execute(() -> {
            rejectedTaskRan.set(true);
            assertThat(Thread.currentThread().getName()).isNotEqualTo(requestThread);
        });

        assertThat(rejectedTaskRan).isFalse();
        release.countDown();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while ((executor.getActiveCount() != 0 || executor.getQueueSize() != 0)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(executor.getActiveCount()).isZero();
        assertThat(executor.getQueueSize()).isZero();
    }
}
