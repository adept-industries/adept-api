package com.adept.api.security.ratelimit;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;

public final class RateLimitException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitException(long retryAfterSeconds) {
        super(ProblemCode.RATE_LIMITED);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
