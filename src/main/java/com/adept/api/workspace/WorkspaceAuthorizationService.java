package com.adept.api.workspace;

import org.springframework.stereotype.Service;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ForbiddenException;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.security.AuthenticatedPrincipal;

@Service
public class WorkspaceAuthorizationService {

    public void requireManager(AuthenticatedPrincipal principal) {
        if (principal == null || principal.role() != MembershipRole.MANAGER) {
            throw new ForbiddenException(ProblemCode.MANAGER_REQUIRED);
        }
    }
}
