package com.adept.api.security;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.MembershipRole;

public record JwtClaims(
    UUID userId,
    UUID membershipId,
    UUID workspaceId,
    MembershipRole role,
    int tokenVersion,
    Instant authenticatedAt,
    Instant issuedAt,
    Instant expiresAt,
    UUID jwtId
) {
    public JwtClaims(
            UUID userId,
            UUID membershipId,
            UUID workspaceId,
            MembershipRole role,
            int tokenVersion,
            Instant issuedAt,
            Instant expiresAt,
            UUID jwtId) {
        this(userId, membershipId, workspaceId, role, tokenVersion, null, issuedAt, expiresAt, jwtId);
    }

    public AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal(
            userId,
            membershipId,
            workspaceId,
            role,
            tokenVersion,
            authenticatedAt
        );
    }
}
