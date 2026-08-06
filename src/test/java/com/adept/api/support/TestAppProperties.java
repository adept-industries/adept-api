package com.adept.api.support;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import com.adept.api.config.AppProperties;

public final class TestAppProperties {

    private TestAppProperties() {
    }

    public static AppProperties create() {
        return create(jwt(Duration.ofMinutes(15)), refreshToken(true), rateLimit());
    }

    public static AppProperties create(AppProperties.Jwt jwt) {
        return create(jwt, refreshToken(true), rateLimit());
    }

    public static AppProperties create(AppProperties.RefreshToken refreshToken) {
        return create(jwt(Duration.ofMinutes(15)), refreshToken, rateLimit());
    }

    public static AppProperties create(AppProperties.RateLimit rateLimit) {
        return create(jwt(Duration.ofMinutes(15)), refreshToken(true), rateLimit);
    }

    public static AppProperties create(
            AppProperties.Jwt jwt,
            AppProperties.RefreshToken refreshToken,
            AppProperties.RateLimit rateLimit) {
        return new AppProperties(
            URI.create("http://localhost:3000"),
            URI.create("http://localhost:8080"),
            "Adept Test <test@adept.local>",
            jwt,
            new AppProperties.Auth(12, Duration.ofHours(24), Duration.ofHours(1), rateLimit),
            refreshToken,
            base64Bytes(32, (byte) 2),
            new AppProperties.IntegrationEncryption(1, Map.of(1, base64Bytes(32, (byte) 3))),
            new AppProperties.Github(false, "", "", "", ""),
            new AppProperties.Jira(false, "", "", URI.create("http://localhost:8080/callback")),
            new AppProperties.Engine(URI.create("http://localhost:8000"), "test-engine-token")
        );
    }

    public static AppProperties.Jwt jwt(Duration ttl) {
        return new AppProperties.Jwt(base64Bytes(64, (byte) 1), "adept-api", "adept-frontend", ttl);
    }

    public static AppProperties.RefreshToken refreshToken(boolean secure) {
        return new AppProperties.RefreshToken(Duration.ofDays(7), "adept_refresh", secure, "Strict");
    }

    public static AppProperties.RateLimit rateLimit() {
        return rateLimit(30_000, 100_000);
    }

    public static AppProperties.RateLimit rateLimit(int peerLimit, int maximumEntries) {
        return new AppProperties.RateLimit(
            peerLimit, Duration.ofMinutes(15),
            10, Duration.ofMinutes(15),
            3, Duration.ofHours(1),
            5, Duration.ofHours(1),
            10, Duration.ofMinutes(15),
            10, Duration.ofMinutes(15),
            maximumEntries
        );
    }

    public static String base64Bytes(int size, byte value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
