package com.adept.api.security.ratelimit;

import java.time.Duration;

public record RateLimitPolicy(String name, int limit, Duration window) {

    public RateLimitPolicy {
        if (name == null || name.isBlank() || limit < 1 || window == null
                || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("A rate-limit policy must have a name, positive limit, and positive window");
        }
    }
}
