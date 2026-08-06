package com.adept.api.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;

@Component
public final class CurrentPrincipal {

    public Optional<AuthenticatedPrincipal> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public AuthenticatedPrincipal require() {
        return optional().orElseThrow(() -> new UnauthorizedException(ProblemCode.SESSION_INVALID));
    }
}
