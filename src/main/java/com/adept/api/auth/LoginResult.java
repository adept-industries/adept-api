package com.adept.api.auth;

import java.time.Instant;

import com.adept.api.auth.dto.AuthSessionResponse;

public record LoginResult(
    AuthSessionResponse response,
    String rawRefreshToken,
    Instant refreshExpiresAt
) {
}
