package com.adept.api.security;

import java.time.Instant;
import java.util.UUID;

import com.adept.api.common.domain.MembershipRole;

public record AuthenticatedPrincipal(
    UUID userId,
    UUID membershipId,
    UUID workspaceId,
    MembershipRole role,
    int tokenVersion,
    Instant authenticatedAt
) {
    public AuthenticatedPrincipal(
            UUID userId,
            UUID membershipId,
            UUID workspaceId,
            MembershipRole role,
            int tokenVersion) {
        this(userId, membershipId, workspaceId, role, tokenVersion, null);
    }
}
