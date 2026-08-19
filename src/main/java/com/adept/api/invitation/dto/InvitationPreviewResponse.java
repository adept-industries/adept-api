package com.adept.api.invitation.dto;

import java.time.Instant;
import java.util.List;

import com.adept.api.common.domain.MembershipRole;

public record InvitationPreviewResponse(
    String email,
    String workspaceName,
    MembershipRole role,
    List<String> repositories,
    Instant expiresAt,
    boolean existingAccount,
    boolean hasPassword
) {
}
