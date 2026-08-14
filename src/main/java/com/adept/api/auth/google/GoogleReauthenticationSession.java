package com.adept.api.auth.google;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record GoogleReauthenticationSession(
    UUID userId,
    UUID workspaceId,
    int tokenVersion,
    Instant expiresAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public boolean isExpired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }
}

