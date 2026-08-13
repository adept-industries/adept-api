package com.adept.api.auth.google;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

public record GoogleSignupSession(
    String subject,
    String email,
    String displayName,
    String avatarUrl,
    Instant expiresAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public boolean isExpired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }
}

