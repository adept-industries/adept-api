package com.adept.api.auth.google;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.ProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class GoogleOAuthStartGuardFilter extends OncePerRequestFilter {

    private final GoogleOAuthSessionService sessionService;
    private final ProblemWriter problemWriter;
    private final String authorizationPath;

    GoogleOAuthStartGuardFilter(
            GoogleOAuthSessionService sessionService,
            ProblemWriter problemWriter,
            String authorizationPath) {
        this.sessionService = sessionService;
        this.problemWriter = problemWriter;
        this.authorizationPath = authorizationPath;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !authorizationPath.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!sessionService.consumeStartMarker(request)) {
            problemWriter.write(request, response, ProblemCode.ENDPOINT_NOT_FOUND);
            return;
        }
        filterChain.doFilter(request, response);
    }
}

