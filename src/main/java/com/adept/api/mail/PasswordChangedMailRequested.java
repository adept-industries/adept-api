package com.adept.api.mail;

import java.util.UUID;

public record PasswordChangedMailRequested(
    UUID userId,
    String recipient,
    String traceId
) {
    @Override
    public String toString() {
        return "PasswordChangedMailRequested[userId=" + userId
            + ", recipient=<redacted>, traceId=" + traceId + "]";
    }
}
