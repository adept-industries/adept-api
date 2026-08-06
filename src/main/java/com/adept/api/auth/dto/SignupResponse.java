package com.adept.api.auth.dto;

import com.adept.api.workspace.dto.WorkspaceSummaryResponse;

public record SignupResponse(
    UserSummary user,
    WorkspaceSummaryResponse workspace,
    boolean emailVerificationRequired
) {
}
