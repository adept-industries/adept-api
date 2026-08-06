package com.adept.api.mail;

import java.util.UUID;

public record VerificationMailRequested(
    UUID userId,
    String recipient,
    String rawToken,
    String traceId
) {
    @Override
    public String toString() {
        return "VerificationMailRequested[userId=" + userId
            + ", recipient=<redacted>, rawToken=<redacted>, traceId=" + traceId + "]";
    }
}
