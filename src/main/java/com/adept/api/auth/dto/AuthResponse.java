package com.adept.api.auth.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/auth/login} and {@code POST /api/v1/auth/refresh}.
 *
 * <p>The refresh token is never included here; the browser receives it only
 * through the {@code Set-Cookie} response header.
 *
 * @param accessToken              signed JWT; keep only in browser memory, never local storage.
 * @param expiresInSeconds         access-token lifetime in seconds (900 = 15 minutes).
 * @param user                     safe subset of the authenticated user's profile.
 * @param currentMembership        the workspace/role context encoded into the token.
 *                                 {@code null} when {@code workspaceSelectionRequired} is {@code true}.
 * @param workspaces               all active memberships; used by the client to offer workspace switching.
 * @param workspaceSelectionRequired {@code true} when the user has multiple workspaces and must
 *                                 pick one before an access token can be issued.
 */
public record AuthResponse(
        String accessToken,
        long expiresInSeconds,
        UserDto user,
        MembershipDto currentMembership,
        List<MembershipDto> workspaces,
        boolean workspaceSelectionRequired
) {

    /**
     * Safe projection of the {@code users} row returned in auth responses.
     * Never includes the password hash or internal audit timestamps.
     */
    public record UserDto(
            UUID id,
            String email,
            String displayName,
            boolean emailVerified
    ) {}

    /**
     * Workspace/role context for one membership.
     * Returned both as {@code currentMembership} and inside the {@code workspaces} list.
     */
    public record MembershipDto(
            UUID id,
            UUID workspaceId,
            String workspaceName,
            String role
    ) {}
}
