package com.adept.api.security.ratelimit;

public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

    public static RateLimitResult permit() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult reject(long retryAfterSeconds) {
        return new RateLimitResult(false, Math.max(1, retryAfterSeconds));
    }
}
