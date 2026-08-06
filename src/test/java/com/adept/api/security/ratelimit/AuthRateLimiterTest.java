package com.adept.api.security.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.ProblemWriter;
import com.adept.api.config.AppProperties;
import com.adept.api.crypto.TokenHasher;
import com.adept.api.support.TestAppProperties;

import static org.assertj.core.api.Assertions.assertThat;
import tools.jackson.databind.json.JsonMapper;

class AuthRateLimiterTest {

    @Test
    void enforcesBoundaryAndResetsAtFixedWindowEnd() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T04:00:00Z"));
        AuthRateLimiter limiter = limiter(2, 100, clock);

        assertThat(limiter.checkPeer("10.0.0.1").allowed()).isTrue();
        assertThat(limiter.checkPeer("10.0.0.1").allowed()).isTrue();
        RateLimitResult rejected = limiter.checkPeer("10.0.0.1");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(900);

        clock.advanceSeconds(900);
        assertThat(limiter.checkPeer("10.0.0.1").allowed()).isTrue();
    }

    @Test
    void incrementsOneBucketAtomicallyInParallel() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T04:00:00Z"));
        AuthRateLimiter limiter = limiter(100, 1_000, clock);
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            attempts.add(() -> limiter.checkPeer("10.0.0.1").allowed());
        }

        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> futures = executor.invokeAll(attempts);
            long allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(100);
        }
    }

    @Test
    void oneSharedCacheIsBoundedAcrossIdentifiers() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T04:00:00Z"));
        AuthRateLimiter limiter = limiter(10, 10, clock);

        for (int index = 0; index < 1_000; index++) {
            limiter.checkPeer("10.0.0." + index);
        }

        assertThat(limiter.estimatedEntryCount()).isLessThanOrEqualTo(10);
    }

    @Test
    void emailIsNormalizedBeforeItsHmacBucketIsSelected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T04:00:00Z"));
        AuthRateLimiter limiter = limiter(100, 100, clock);

        for (int attempt = 0; attempt < 10; attempt++) {
            String spelling = attempt % 2 == 0 ? " User@Example.COM " : "user@example.com";
            assertThat(limiter.checkLogin(spelling).allowed()).isTrue();
        }
        assertThat(limiter.checkLogin("USER@EXAMPLE.COM").allowed()).isFalse();
    }

    @Test
    void actionTokenBucketUsesTheSuppliedPurposeSpecificHash() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T04:00:00Z"));
        AppProperties properties = TestAppProperties.create();
        TokenHasher hasher = new TokenHasher(properties);
        AuthRateLimiter limiter = new AuthRateLimiter(properties, hasher, clock);
        String rawToken = "same-raw-token";
        String verificationHash = hasher.hashVerificationToken(rawToken);
        String resetHash = hasher.hashResetToken(rawToken);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(limiter.checkActionTokenHash(verificationHash).allowed()).isTrue();
            assertThat(limiter.checkActionTokenHash(resetHash).allowed()).isTrue();
        }
        assertThat(limiter.checkActionTokenHash(verificationHash).allowed()).isFalse();
        assertThat(limiter.checkActionTokenHash(resetHash).allowed()).isFalse();
    }

    @Test
    void forwardedHeadersCannotSelectANewPeerBucket() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-06T04:00:00Z"));
        AuthRateLimiter limiter = limiter(1, 100, clock);
        ProblemWriter writer = new ProblemWriter(
            new JsonMapper(), new com.adept.api.common.error.ProblemResponseFactory());
        AuthPeerRateLimitFilter filter = new AuthPeerRateLimitFilter(limiter, writer);

        MockHttpServletRequest first = unsafeAuthRequest();
        first.setRemoteAddr("172.18.0.2");
        first.addHeader("Forwarded", "for=198.51.100.1");
        first.addHeader("X-Forwarded-For", "198.51.100.1");
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(first, new MockHttpServletResponse(), firstChain);
        assertThat(firstChain.getRequest()).isNotNull();

        MockHttpServletRequest second = unsafeAuthRequest();
        second.setRemoteAddr("172.18.0.2");
        second.addHeader("Forwarded", "for=203.0.113.55");
        second.addHeader("X-Forwarded-For", "203.0.113.55");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilter(second, response, secondChain);

        assertThat(secondChain.getRequest()).isNull();
        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        assertThat(response.getStatus()).isEqualTo(ProblemCode.RATE_LIMITED.status().value());
        assertThat(response.getContentAsString()).contains("RATE_LIMITED");
    }

    private static MockHttpServletRequest unsafeAuthRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRequestURI("/api/v1/auth/login");
        return request;
    }

    private static AuthRateLimiter limiter(int peerLimit, int maximumEntries, Clock clock) {
        AppProperties properties = TestAppProperties.create(
            TestAppProperties.rateLimit(peerLimit, maximumEntries));
        return new AuthRateLimiter(properties, new TokenHasher(properties), clock);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
