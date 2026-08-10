package com.adept.api.auth.dto;

import java.util.UUID;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.workspace.Membership;

public record MembershipSummary(
    UUID id,
    UUID workspaceId,
    String workspaceName,
    String workspaceSlug,
    String timezone,
    MembershipRole role
) {
    public static MembershipSummary from(Membership membership) {
        if (membership == null) {
            return null;
        }
        return new MembershipSummary(
            membership.getId(),
            membership.getWorkspace().getId(),
            membership.getWorkspace().getName(),
            membership.getWorkspace().getSlug(),
            membership.getWorkspace().getTimezone(),
            membership.getRole()
        );
    }
}
