package com.adept.api.security;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.adept.api.common.domain.MembershipRole;

import jakarta.servlet.FilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enforcesTheBearerBoundaryWithoutMaskingDownstreamFailures() throws Exception {
        JwtAuthenticationFilter filter = filterWithMocks();

        assertThat(filter.shouldNotFilter(request("/api/v1/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/auth/me"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/workspaces"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/workspaces/current"))).isFalse();

        AuthenticationEntryPoint entryPoint = mock(AuthenticationEntryPoint.class);
        JwtAuthenticationFilter rejectingFilter = new JwtAuthenticationFilter(
            mock(JwtService.class),
            mock(PrincipalValidationService.class),
            entryPoint
        );
        MockHttpServletRequest request = request("/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer first");
        request.addHeader("Authorization", "Bearer second");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        rejectingFilter.doFilter(request, response, chain);

        verify(entryPoint).commence(any(), any(), any());
        verify(chain, never()).doFilter(any(), any());

        JwtService jwtService = mock(JwtService.class);
        PrincipalValidationService validationService = mock(PrincipalValidationService.class);
        AuthenticationEntryPoint downstreamEntryPoint = mock(AuthenticationEntryPoint.class);
        JwtAuthenticationFilter downstreamFilter = new JwtAuthenticationFilter(
            jwtService, validationService, downstreamEntryPoint);

        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        Instant authenticatedAt = Instant.now().minusSeconds(30);
        JwtClaims claims = new JwtClaims(
            userId,
            membershipId,
            workspaceId,
            MembershipRole.MANAGER,
            0,
            authenticatedAt,
            Instant.now(),
            Instant.now().plusSeconds(900),
            UUID.randomUUID()
        );
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            userId, membershipId, workspaceId, MembershipRole.MANAGER, 0, authenticatedAt);
        when(jwtService.parse("valid-token")).thenReturn(claims);
        when(validationService.validate(
            userId, membershipId, workspaceId, MembershipRole.MANAGER, 0, authenticatedAt
        )).thenReturn(Optional.of(new PrincipalValidationService.ValidatedPrincipal(principal, null)));

        MockHttpServletRequest downstreamRequest = request("/api/v1/auth/me");
        downstreamRequest.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse downstreamResponse = new MockHttpServletResponse();
        FilterChain downstreamChain = mock(FilterChain.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("downstream failure"))
            .when(downstreamChain).doFilter(any(), any());

        assertThatThrownBy(() -> downstreamFilter.doFilter(
                downstreamRequest, downstreamResponse, downstreamChain))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("downstream failure");
        verify(downstreamEntryPoint, never()).commence(any(), any(), any());
    }

    private static JwtAuthenticationFilter filterWithMocks() {
        return new JwtAuthenticationFilter(
            mock(JwtService.class),
            mock(PrincipalValidationService.class),
            mock(AuthenticationEntryPoint.class)
        );
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
