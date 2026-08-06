package com.adept.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.adept.api.support.TestAppProperties;

class AccountMailListenerTest {

    @Test
    void verificationLinkUsesFragmentOnConfiguredFrontendOrigin() {
        CapturingMailSender sender = new CapturingMailSender(false);
        AccountMailService service = new AccountMailService(sender, TestAppProperties.create());

        service.sendVerification(new VerificationMailRequested(
            java.util.UUID.randomUUID(),
            "alice@example.com",
            "RAW_TOKEN",
            "trace-1"
        ));

        assertThat(sender.lastMessage.getText())
            .contains("http://localhost:3000/verify-email#token=RAW_TOKEN")
            .doesNotContain("?token=");
    }

    @Test
    void listenerSwallowsMailFailureAfterCommitPath() {
        AccountMailService service = new AccountMailService(
            new CapturingMailSender(true),
            TestAppProperties.create()
        );
        AccountMailListener listener = new AccountMailListener(service);

        listener.onPasswordReset(new PasswordResetMailRequested(
            java.util.UUID.randomUUID(),
            "alice@example.com",
            "RAW_TOKEN",
            "trace-1"
        ));
    }

    private static final class CapturingMailSender implements JavaMailSender {
        private final boolean fail;
        private SimpleMailMessage lastMessage;

        private CapturingMailSender(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void send(SimpleMailMessage simpleMessage) {
            if (fail) {
                throw new MailSendException("smtp down");
            }
            this.lastMessage = simpleMessage;
        }

        @Override
        public void send(SimpleMailMessage... simpleMessages) {
            send(simpleMessages[0]);
        }

        @Override
        public jakarta.mail.internet.MimeMessage createMimeMessage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.mail.internet.MimeMessage createMimeMessage(java.io.InputStream contentStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(jakarta.mail.internet.MimeMessage mimeMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(jakarta.mail.internet.MimeMessage... mimeMessages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(org.springframework.mail.javamail.MimeMessagePreparator mimeMessagePreparator) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(org.springframework.mail.javamail.MimeMessagePreparator... mimeMessagePreparators) {
            throw new UnsupportedOperationException();
        }
    }
}
