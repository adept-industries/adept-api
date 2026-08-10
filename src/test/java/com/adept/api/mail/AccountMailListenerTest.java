package com.adept.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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

    @Test
    void mailContentUsesSafeFragmentLinksAndRedactsDiagnosticStrings() {
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
    void transactionalMailRunsAfterCommitAndNotAfterRollback() throws Exception {
        String commitRecipient = uniqueEmail("mail-after-commit");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new VerificationMailRequested(
                UUID.randomUUID(), commitRecipient, "RAW_TOKEN", "trace-after-commit"));
            assertThat(mailSender.messages()).noneMatch(
                message -> message.recipients().contains(commitRecipient));
        });

        var delivered = mailSender.await(
            message -> message.recipients().contains(commitRecipient),
            Duration.ofSeconds(5)
        );
        assertThat(delivered.threadName()).startsWith("account-mail-");

        String rollbackRecipient = uniqueEmail("mail-rollback");

        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new PasswordResetMailRequested(
                UUID.randomUUID(), rollbackRecipient, "RAW_TOKEN", "trace-rollback"));
            status.setRollbackOnly();
        });

        Thread.sleep(200);
        assertThat(mailSender.messages()).noneMatch(
            message -> message.recipients().contains(rollbackRecipient));
    }
}
