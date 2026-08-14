package com.adept.api.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    @NotNull URI frontendBaseUrl,
    @NotNull URI publicApiBaseUrl,
    @NotBlank String emailFrom,
    @Valid @NotNull Jwt jwt,
    @Valid @NotNull Auth auth,
    @Valid @NotNull RefreshToken refreshToken,
    @NotBlank String tokenHashPepperBase64,
    @Valid @NotNull IntegrationEncryption integrationEncryption,
    @Valid @NotNull Github github,
    @Valid @NotNull Jira jira,
    @Valid @NotNull Engine engine
) {
    public AppProperties {
        frontendBaseUrl = normalizeBrowserOrigin(frontendBaseUrl);
    }

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

        @NotBlank
        @Pattern(
            regexp = "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$",
            message = "must be a legal cookie name"
        )
        String cookieName,

        boolean cookieSecure,

        @NotBlank
        @Pattern(regexp = "Strict", message = "must be Strict")
        String cookieSameSite
    ) {
        @AssertTrue(message = "app.refresh-token.ttl must be positive")
        public boolean isTtlValid() {
            return isPositive(ttl);
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
        public boolean isAllKeysValid() {
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

    public record Auth(
        @Min(4) @Max(31) int bcryptCost,
        @NotNull Duration verificationTokenTtl,
        @NotNull Duration resetTokenTtl,
        @NotNull Duration sensitiveActionMaxAge,
        @Valid @NotNull RateLimit rateLimit
    ) {
        public Auth {
            if (sensitiveActionMaxAge == null) {
                sensitiveActionMaxAge = Duration.ofMinutes(5);
            }
        }

        @AssertTrue(message = "app.auth.verification-token-ttl must be positive")
        public boolean isVerificationTokenTtlValid() {
            return isPositive(verificationTokenTtl);
        }

        @AssertTrue(message = "app.auth.reset-token-ttl must be positive")
        public boolean isResetTokenTtlValid() {
            return isPositive(resetTokenTtl);
        }

        @AssertTrue(message = "app.auth.sensitive-action-max-age must be positive")
        public boolean isSensitiveActionMaxAgeValid() {
            return isPositive(sensitiveActionMaxAge);
        }
    }

    public record RateLimit(
        @Positive int authPeerLimit,
        @NotNull Duration authPeerWindow,
        @Positive int loginAccountLimit,
        @NotNull Duration loginWindow,
        @Positive int signupEmailLimit,
        @NotNull Duration signupWindow,
        @Positive int accountEmailLimit,
        @NotNull Duration accountEmailWindow,
        @Positive int actionTokenLimit,
        @NotNull Duration actionTokenWindow,
        @Positive int deletionUserLimit,
        @NotNull Duration deletionUserWindow,
        @Positive int maximumEntries
    ) {
        @AssertTrue(message = "all app.auth.rate-limit windows must be positive")
        public boolean isWindowsValid() {
            return isPositive(authPeerWindow)
                && isPositive(loginWindow)
                && isPositive(signupWindow)
                && isPositive(accountEmailWindow)
                && isPositive(actionTokenWindow)
                && isPositive(deletionUserWindow);
        }
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

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static URI normalizeBrowserOrigin(URI value) {
        if (value == null) {
            return null;
        }

        String scheme = value.getScheme();
        if (scheme == null
            || (!scheme.equalsIgnoreCase("http")
                && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                "app.frontend-base-url must use http or https"
            );
        }

        String rawPath = value.getRawPath();

        if (!value.isAbsolute()
            || value.getHost() == null
            || value.getRawUserInfo() != null
            || value.getRawQuery() != null
            || value.getRawFragment() != null
            || !(rawPath == null || rawPath.isEmpty() || rawPath.equals("/"))) {
            throw new IllegalArgumentException(
                "app.frontend-base-url must be a browser origin"
            );
        }

        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = value.getHost().toLowerCase(Locale.ROOT);

        int defaultPort = normalizedScheme.equals("https") ? 443 : 80;
        int effectivePort = value.getPort() == -1
            ? defaultPort
            : value.getPort();

        if (effectivePort < 1 || effectivePort > 65_535) {
            throw new IllegalArgumentException(
                "app.frontend-base-url contains an invalid port"
            );
        }

        int normalizedPort = effectivePort == defaultPort
            ? -1
            : effectivePort;

        try {
            return new URI(
                normalizedScheme,
                null,
                normalizedHost,
                normalizedPort,
                "/",
                null,
                null
            );
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                "app.frontend-base-url could not be normalized",
                exception
            );
        }
    }
}
