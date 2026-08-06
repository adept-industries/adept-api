package com.adept.api.workspace.dto;

import java.util.UUID;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;

public record WorkspaceSummaryResponse(
    UUID id,
    String name,
    String slug,
    String timezone,
    MembershipRole role
) {
    public static WorkspaceSummaryResponse from(Membership membership) {
        Workspace workspace = membership.getWorkspace();
        return new WorkspaceSummaryResponse(
            workspace.getId(),
            workspace.getName(),
            workspace.getSlug(),
            workspace.getTimezone(),
            membership.getRole()
        );
    }
}
