package com.adept.api.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AccountMailListener {

    private static final Logger log = LoggerFactory.getLogger(AccountMailListener.class);

    private final AccountMailService mailService;

    public AccountMailListener(AccountMailService mailService) {
        this.mailService = mailService;
    }

    @Async("accountMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerification(VerificationMailRequested event) {
        try {
            mailService.sendVerification(event);
        } catch (MailException exception) {
            log.warn("verification_mail_failed userId={} traceId={}", event.userId(), event.traceId());
        }
    }

    @Async("accountMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordReset(PasswordResetMailRequested event) {
        try {
            mailService.sendPasswordReset(event);
        } catch (MailException exception) {
            log.warn("password_reset_mail_failed userId={} traceId={}", event.userId(), event.traceId());
        }
    }

    @Async("accountMailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChangedMailRequested event) {
        try {
            mailService.sendPasswordChanged(event);
        } catch (MailException exception) {
            log.warn("password_changed_mail_failed userId={} traceId={}", event.userId(), event.traceId());
        }
    }
}
