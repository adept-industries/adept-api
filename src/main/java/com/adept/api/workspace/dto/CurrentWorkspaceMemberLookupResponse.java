package com.adept.api.workspace.dto;

import java.util.UUID;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.domain.MembershipStatus;

import io.swagger.v3.oas.annotations.media.Schema;

public record CurrentWorkspaceMemberLookupResponse(
    String email,
    boolean existingUser,
    boolean emailVerified,
    @Schema(nullable = true) UUID workspaceMembershipId,
    @Schema(nullable = true) MembershipRole workspaceRole,
    @Schema(nullable = true) MembershipStatus workspaceMembershipStatus,
    boolean assignableAsLead
) {
}
