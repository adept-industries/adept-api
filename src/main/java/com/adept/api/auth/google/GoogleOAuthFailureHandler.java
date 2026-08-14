package com.adept.api.auth.google;

import java.io.IOException;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import com.adept.api.config.AppProperties;
import com.adept.api.security.CsrfCookieService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class GoogleOAuthFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthFailureHandler.class);

    private final GoogleOAuthSessionService oauthSessionService;
    private final CsrfCookieService csrfCookieService;
    private final URI frontendBaseUrl;

    GoogleOAuthFailureHandler(
            GoogleOAuthSessionService oauthSessionService,
            CsrfCookieService csrfCookieService,
            AppProperties appProperties) {
        this.oauthSessionService = oauthSessionService;
        this.csrfCookieService = csrfCookieService;
        this.frontendBaseUrl = appProperties.frontendBaseUrl();
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        log.warn("Google OAuth handshake failed: errorCode={}", safeErrorCode(exception));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        boolean reauthentication = oauthSessionService.pendingReauthentication(request).isPresent();
        oauthSessionService.clear(request, response);
        csrfCookieService.expire(request, response);
        String relativePath = reauthentication
            ? "dashboard/settings?google_reauth_error=authentication_failed"
            : "login?google_error=authentication_failed";
        response.sendRedirect(frontendBaseUrl.resolve(relativePath).toString());
    }

    private static String safeErrorCode(Throwable exception) {
        Throwable current = exception;
        String deepestCode = null;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String code = switch (current) {
                case OAuth2AuthenticationException authentication -> authentication.getError().getErrorCode();
                case OAuth2AuthorizationException authorization -> authorization.getError().getErrorCode();
                default -> null;
            };
            if (code != null && code.matches("[A-Za-z0-9_.-]{1,80}")) {
                deepestCode = code;
            }
            current = current.getCause();
        }
        return deepestCode == null ? "unclassified" : deepestCode;
    }
}
