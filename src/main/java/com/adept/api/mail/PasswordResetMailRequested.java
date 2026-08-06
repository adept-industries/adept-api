package com.adept.api.mail;

import java.util.UUID;

public record PasswordResetMailRequested(
    UUID userId,
    String recipient,
    String rawToken,
    String traceId
) {
    @Override
    public String toString() {
        return "PasswordResetMailRequested[userId=" + userId
            + ", recipient=<redacted>, rawToken=<redacted>, traceId=" + traceId + "]";
    }
}
