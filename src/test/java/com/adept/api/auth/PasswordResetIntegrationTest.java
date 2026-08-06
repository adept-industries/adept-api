package com.adept.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.adept.api.auth.dto.ActionTokenRequest;
import com.adept.api.auth.dto.EmailRequest;
import com.adept.api.auth.dto.ResetPasswordRequest;
import com.adept.api.mail.PasswordResetMailRequested;

class PasswordResetIntegrationTest {

    @Test
    void credentialBearingRequestsRedactSensitiveValues() {
        assertThat(new ActionTokenRequest("raw-token").toString()).doesNotContain("raw-token");
        assertThat(new ResetPasswordRequest("raw-token", "new-password").toString())
            .doesNotContain("raw-token", "new-password");
        assertThat(new EmailRequest("alice@example.com").toString())
            .doesNotContain("alice@example.com");
    }

    @Test
    void resetMailEventRedactsRawTokenAndRecipient() {
        PasswordResetMailRequested event = new PasswordResetMailRequested(
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
