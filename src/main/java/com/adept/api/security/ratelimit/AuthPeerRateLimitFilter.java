package com.adept.api.security.ratelimit;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.ProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class AuthPeerRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of(
        HttpMethod.POST.name(),
        HttpMethod.PUT.name(),
        HttpMethod.PATCH.name(),
        HttpMethod.DELETE.name()
    );

    private final AuthRateLimiter rateLimiter;
    private final ProblemWriter problemWriter;

    public AuthPeerRateLimitFilter(AuthRateLimiter rateLimiter, ProblemWriter problemWriter) {
        this.rateLimiter = rateLimiter;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !UNSAFE_METHODS.contains(request.getMethod())
            || !request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RateLimitResult result = rateLimiter.checkPeer(request.getRemoteAddr());
        if (!result.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(result.retryAfterSeconds()));
            problemWriter.write(request, response, ProblemCode.RATE_LIMITED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
