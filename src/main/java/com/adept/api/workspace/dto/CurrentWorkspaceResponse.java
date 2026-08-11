package com.adept.api.workspace.dto;

import java.util.UUID;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.workspace.Membership;
import com.adept.api.workspace.Workspace;

public record CurrentWorkspaceResponse(
    UUID id,
    String name,
    String slug,
    String timezone,
    MembershipRole role,
    UUID membershipId
) {
    public static CurrentWorkspaceResponse from(Membership membership) {
        Workspace workspace = membership.getWorkspace();
        return new CurrentWorkspaceResponse(
            workspace.getId(),
            workspace.getName(),
            workspace.getSlug(),
            workspace.getTimezone(),
            membership.getRole(),
            membership.getId()
        );
    }
}
