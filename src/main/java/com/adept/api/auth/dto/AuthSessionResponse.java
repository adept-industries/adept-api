package com.adept.api.auth.dto;

import java.util.List;

import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

public record AuthSessionResponse(
    String accessToken,
    Integer expiresInSeconds,
    boolean workspaceSelectionRequired,
    UserSummary user,
    MembershipSummary currentMembership,
    List<WorkspaceSummaryResponse> workspaces
) {
}
