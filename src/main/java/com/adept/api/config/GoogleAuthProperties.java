package com.adept.api.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "app.google-auth")
public record GoogleAuthProperties(
    boolean enabled,
    String clientId,
    String clientSecret,
    @NotNull URI redirectUri,
    @NotNull Duration onboardingTtl
) {

    @AssertTrue(message = "Google client ID and secret are required when app.google-auth.enabled=true")
    public boolean isCredentialsCompleteWhenEnabled() {
        return !enabled || hasText(clientId) && hasText(clientSecret);
    }

    @AssertTrue(message = "app.google-auth.onboarding-ttl must be positive")
    public boolean isOnboardingTtlPositive() {
        return onboardingTtl != null && !onboardingTtl.isZero() && !onboardingTtl.isNegative();
    }

    @AssertTrue(message = "app.google-auth.redirect-uri must use HTTPS, except on loopback hosts")
    public boolean isRedirectUriSafe() {
        if (redirectUri == null || !redirectUri.isAbsolute() || redirectUri.getHost() == null
                || redirectUri.getRawUserInfo() != null || redirectUri.getRawFragment() != null) {
            return false;
        }
        if ("https".equalsIgnoreCase(redirectUri.getScheme())) {
            return true;
        }
        String host = redirectUri.getHost();
        return "http".equalsIgnoreCase(redirectUri.getScheme())
            && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

