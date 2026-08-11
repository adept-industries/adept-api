package com.adept.api.workspace.dto;

import java.util.UUID;

import com.adept.api.common.domain.WorkspaceStatus;

public record WorkspaceDeletionResponse(
    UUID workspaceId,
    WorkspaceStatus status
) {}
