package com.adept.api.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import com.adept.api.config.AppProperties;
import com.adept.api.crypto.SecureTokenGenerator;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Service
public final class RefreshCookieService {

    public static final String COOKIE_PATH = "/api/v1/auth";

    private final AppProperties.RefreshToken properties;
    private final Clock clock;

    public RefreshCookieService(AppProperties appProperties, Clock clock) {
        this.properties = appProperties.refreshToken();
        this.clock = clock;
    }

    public void set(HttpServletResponse response, String rawToken, Instant absoluteFamilyExpiry) {
        if (!SecureTokenGenerator.isWellFormed(rawToken)) {
            throw new IllegalArgumentException("refresh token must use the required transport shape");
        }
        Duration remaining = Duration.between(clock.instant(), absoluteFamilyExpiry);
        if (remaining.isZero() || remaining.isNegative()) {
            clear(response);
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(rawToken, remaining).toString());
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String found = null;
        for (Cookie cookie : cookies) {
            if (!properties.cookieName().equals(cookie.getName())) {
                continue;
            }
            if (found != null || !SecureTokenGenerator.isWellFormed(cookie.getValue())) {
                return Optional.empty();
            }
            found = cookie.getValue();
        }
        return Optional.ofNullable(found);
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(properties.cookieName(), value)
            .httpOnly(true)
            .secure(properties.cookieSecure())
            .sameSite(properties.cookieSameSite())
            .path(COOKIE_PATH)
            .maxAge(maxAge)
            .build();
    }
}
