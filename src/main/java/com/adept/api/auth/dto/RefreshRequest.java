package com.adept.api.auth.dto;

import java.util.UUID;

public record RefreshRequest(
    UUID workspaceId
) {
}
