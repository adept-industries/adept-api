package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.adept.api.mail.VerificationMailRequested;

class EmailVerificationIntegrationTest {

    @Test
    void verificationMailEventRedactsRawTokenAndRecipient() {
        VerificationMailRequested event = new VerificationMailRequested(
            java.util.UUID.randomUUID(),
            "alice@example.com",
            "raw-secret-token",
            "trace-1"
        );

        assertThat(event.toString())
            .doesNotContain("alice@example.com", "raw-secret-token")
            .contains("rawToken=<redacted>");
    }
}
