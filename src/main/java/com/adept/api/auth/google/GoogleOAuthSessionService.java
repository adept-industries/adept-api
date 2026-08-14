package com.adept.api.auth.google;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import com.adept.api.config.AppProperties;
import com.adept.api.config.GoogleAuthProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Service
public final class GoogleOAuthSessionService {

    public static final String COOKIE_NAME = "adept_oauth";
    public static final String COOKIE_PATH = "/api/v1/auth/google";
    private static final String STARTED_ATTRIBUTE = GoogleOAuthSessionService.class.getName() + ".started";
    private static final String SIGNUP_ATTRIBUTE = GoogleOAuthSessionService.class.getName() + ".signup";
    private static final String REAUTHENTICATION_ATTRIBUTE =
        GoogleOAuthSessionService.class.getName() + ".reauthentication";

    private final boolean secureCookie;
    private final Duration ttl;

    public GoogleOAuthSessionService(
            AppProperties appProperties,
            GoogleAuthProperties googleAuthProperties) {
        this.secureCookie = appProperties.refreshToken().cookieSecure();
        this.ttl = googleAuthProperties.onboardingTtl();
    }

    public void begin(HttpServletRequest request) {
        HttpSession session = newSession(request);
        session.setAttribute(STARTED_ATTRIBUTE, Boolean.TRUE);
    }

    public void beginReauthentication(
            HttpServletRequest request,
            GoogleReauthenticationSession pending) {
        HttpSession session = newSession(request);
        session.setAttribute(STARTED_ATTRIBUTE, Boolean.TRUE);
        session.setAttribute(REAUTHENTICATION_ATTRIBUTE, pending);
    }

    private HttpSession newSession(HttpServletRequest request) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(ttlSeconds());
        return session;
    }

    public boolean consumeStartMarker(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(STARTED_ATTRIBUTE))) {
            return false;
        }
        session.removeAttribute(STARTED_ATTRIBUTE);
        return true;
    }

    public void keepForSignup(HttpServletRequest request, GoogleSignupSession pending) {
        HttpSession session = request.getSession(true);
        Collections.list(session.getAttributeNames()).forEach(session::removeAttribute);
        request.changeSessionId();
        session.setMaxInactiveInterval(ttlSeconds());
        session.setAttribute(SIGNUP_ATTRIBUTE, pending);
    }

    public Optional<GoogleSignupSession> pendingSignup(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object value = session.getAttribute(SIGNUP_ATTRIBUTE);
        return value instanceof GoogleSignupSession pending
            ? Optional.of(pending)
            : Optional.empty();
    }

    public Optional<GoogleReauthenticationSession> pendingReauthentication(
            HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object value = session.getAttribute(REAUTHENTICATION_ATTRIBUTE);
        return value instanceof GoogleReauthenticationSession pending
            ? Optional.of(pending)
            : Optional.empty();
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, "")
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .path(COOKIE_PATH)
            .maxAge(Duration.ZERO)
            .build()
            .toString());
    }

    private int ttlSeconds() {
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1L, ttl.toSeconds())));
    }
}
