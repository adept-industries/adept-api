package com.adept.api.config;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    @NotNull URI frontendBaseUrl,
    @NotNull URI publicApiBaseUrl,
    @NotBlank String emailFrom,
    @Valid @NotNull Jwt jwt,
    @Valid @NotNull RefreshToken refreshToken,
    @NotBlank String tokenHashPepperBase64,
    @Valid @NotNull IntegrationEncryption integrationEncryption,
    @Valid @NotNull Github github,
    @Valid @NotNull Jira jira,
    @Valid @NotNull Engine engine
) {
    @AssertTrue(message = "app.token-hash-pepper-base64 must decode to at least 32 bytes")
    public boolean isTokenHashPepperValid() {
        return decodesToAtLeast32Bytes(tokenHashPepperBase64);
    }

    public record Jwt(
        @NotBlank String secretBase64,
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTokenTtl
    ) {
        @AssertTrue(message = "app.jwt.secret-base64 must decode to at least 32 bytes")
        public boolean isSecretValid() {
            return decodesToAtLeast32Bytes(secretBase64);
        }

        @AssertTrue(message = "app.jwt.access-token-ttl must be positive")
        public boolean isAccessTokenTtlValid() {
            return accessTokenTtl != null
                && !accessTokenTtl.isZero()
                && !accessTokenTtl.isNegative();
        }
    }

    public record RefreshToken(
        @NotNull Duration ttl,
        @NotBlank String cookieName,
        boolean cookieSecure,
        @NotBlank String cookieSameSite
    ) {
        @AssertTrue(message = "app.refresh-token.ttl must be positive")
        public boolean isTtlValid() {
            return ttl != null && !ttl.isZero() && !ttl.isNegative();
        }
    }

    public record IntegrationEncryption(
        @Positive int activeKeyVersion,
        @NotEmpty Map<Integer, String> keys
    ) {
        @AssertTrue(message = "the active integration-encryption key must exist and decode to at least 32 bytes")
        public boolean isActiveKeyValid() {
            if (keys == null) {
                return false;
            }
            return decodesToAtLeast32Bytes(keys.get(activeKeyVersion));
        }

        @AssertTrue(message = "every integration-encryption key must decode to at least 32 bytes")
        public boolean areAllKeysValid() {
            return keys != null
                && !keys.isEmpty()
                && keys.values().stream().allMatch(AppProperties::decodesToAtLeast32Bytes);
        }
    }

    public record Github(
        boolean enabled,
        String appId,
        String appSlug,
        String privateKeyBase64,
        String webhookSecret
    ) {
        @AssertTrue(message = "all GitHub settings are required when app.github.enabled=true")
        public boolean isCompleteWhenEnabled() {
            return !enabled
                || allHaveText(appId, appSlug, privateKeyBase64, webhookSecret);
        }
    }

    public record Jira(
        boolean enabled,
        String clientId,
        String clientSecret,
        @NotNull URI callbackUrl
    ) {
        @AssertTrue(message = "all Jira settings are required when app.jira.enabled=true")
        public boolean isCompleteWhenEnabled() {
            return !enabled || allHaveText(clientId, clientSecret);
        }
    }

    public record Engine(
        @NotNull URI baseUrl,
        @NotBlank String internalToken
    ) {
    }

    private static boolean allHaveText(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean decodesToAtLeast32Bytes(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(value).length >= 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}