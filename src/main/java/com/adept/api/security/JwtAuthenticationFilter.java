package com.adept.api.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final PrincipalValidationService principalValidationService;
    private final AuthenticationEntryPoint entryPoint;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            PrincipalValidationService principalValidationService,
            AuthenticationEntryPoint entryPoint) {
        this.jwtService = jwtService;
        this.principalValidationService = principalValidationService;
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/api/v1/auth/me".equals(path)
            || "/api/v1/auth/test-me".equals(path)
            || "/api/v1/auth/reauthenticate/password".equals(path)
            || "/api/v1/auth/google/reauthentication/start".equals(path)
            || "/api/v1/workspaces".equals(path)
            || path.startsWith("/api/v1/workspaces/")
            || "/api/v1/projects".equals(path)
            || path.startsWith("/api/v1/projects/")
            || "/api/v1/metrics".equals(path)
            || path.startsWith("/api/v1/metrics/")
            || "/api/v1/integrations".equals(path)
            || path.startsWith("/api/v1/integrations/")
            || "/api/v1/repositories".equals(path)
            || path.startsWith("/api/v1/repositories/")
            || "/api/v1/invitations".equals(path)
            || path.startsWith("/api/v1/invitations/")
            || "/api/v1/jira".equals(path)
            || path.startsWith("/api/v1/jira/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        if (headers.size() != 1) {
            reject(request, response);
            return;
        }

        String header = headers.getFirst();
        if (header == null || header.isBlank() || header.contains(",") || !header.startsWith("Bearer ")) {
            reject(request, response);
            return;
        }

        String token = header.substring(7).trim();
        if (token.isEmpty() || token.contains(" ")) {
            reject(request, response);
            return;
        }

        PrincipalValidationService.ValidatedPrincipal validated;
        try {
            JwtClaims claims = jwtService.parse(token);
            validated = principalValidationService
                .validate(
                    claims.userId(),
                    claims.membershipId(),
                    claims.workspaceId(),
                    claims.role(),
                    claims.tokenVersion(),
                    claims.authenticatedAt()
                )
                .orElseThrow(() -> new UnauthorizedException(ProblemCode.SESSION_INVALID));
        } catch (RuntimeException exception) {
            reject(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
            validated.principal(),
            null,
            List.of(new SimpleGrantedAuthority(validated.principal().role().name()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        entryPoint.commence(request, response, new org.springframework.security.core.AuthenticationException("Unauthorized") {});
    }
}
