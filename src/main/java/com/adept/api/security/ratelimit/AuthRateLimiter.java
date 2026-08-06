package com.adept.api.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.adept.api.config.AppProperties;
import com.adept.api.crypto.TokenHasher;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@Service
public final class AuthRateLimiter {

    private final Cache<String, FixedWindow> counters;
    private final Clock clock;
    private final TokenHasher tokenHasher;
    private final RateLimitPolicy authPeer;
    private final RateLimitPolicy loginAccount;
    private final RateLimitPolicy signupEmail;
    private final RateLimitPolicy accountEmail;
    private final RateLimitPolicy actionToken;
    private final RateLimitPolicy deletionUser;

    public AuthRateLimiter(AppProperties properties, TokenHasher tokenHasher, Clock clock) {
        AppProperties.RateLimit configured = properties.auth().rateLimit();
        this.clock = clock;
        this.tokenHasher = tokenHasher;
        this.authPeer = new RateLimitPolicy(
            "auth-peer", configured.authPeerLimit(), configured.authPeerWindow());
        this.loginAccount = new RateLimitPolicy(
            "login-account", configured.loginAccountLimit(), configured.loginWindow());
        this.signupEmail = new RateLimitPolicy(
            "signup-email", configured.signupEmailLimit(), configured.signupWindow());
        this.accountEmail = new RateLimitPolicy(
            "account-email", configured.accountEmailLimit(), configured.accountEmailWindow());
        this.actionToken = new RateLimitPolicy(
            "action-token", configured.actionTokenLimit(), configured.actionTokenWindow());
        this.deletionUser = new RateLimitPolicy(
            "deletion-user", configured.deletionUserLimit(), configured.deletionUserWindow());

        Duration longestWindow = List.of(
            authPeer, loginAccount, signupEmail, accountEmail, actionToken, deletionUser
        ).stream().map(RateLimitPolicy::window).max(Duration::compareTo).orElseThrow();
        this.counters = Caffeine.newBuilder()
            .maximumSize(configured.maximumEntries())
            .expireAfterAccess(longestWindow)
            .build();
    }

    public RateLimitResult checkPeer(String transportPeerAddress) {
        String safeValue = transportPeerAddress == null ? "unknown-peer" : transportPeerAddress;
        return check(authPeer, tokenHasher.hashAuditIp(safeValue));
    }

    public RateLimitResult checkLogin(String email) {
        return check(loginAccount, hashNormalizedEmail(email));
    }

    public RateLimitResult checkSignup(String email) {
        return check(signupEmail, hashNormalizedEmail(email));
    }

    public RateLimitResult checkAccountEmail(String email) {
        return check(accountEmail, hashNormalizedEmail(email));
    }

    public RateLimitResult checkActionTokenHash(String tokenHash) {
        String safeValue = tokenHash == null ? "missing-token-hash" : tokenHash;
        return check(actionToken, safeValue);
    }

    public RateLimitResult checkDeletion(UUID authenticatedUserId) {
        String key = authenticatedUserId == null ? "missing-user" : authenticatedUserId.toString();
        return check(deletionUser, key);
    }

    public void requirePeer(String transportPeerAddress) {
        requireAllowed(checkPeer(transportPeerAddress));
    }

    public void requireLogin(String email) {
        requireAllowed(checkLogin(email));
    }

    public void requireSignup(String email) {
        requireAllowed(checkSignup(email));
    }

    public void requireAccountEmail(String email) {
        requireAllowed(checkAccountEmail(email));
    }

    public void requireActionTokenHash(String tokenHash) {
        requireAllowed(checkActionTokenHash(tokenHash));
    }

    public void requireDeletion(UUID authenticatedUserId) {
        requireAllowed(checkDeletion(authenticatedUserId));
    }

    private String hashNormalizedEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return tokenHasher.hashAuditEmail(normalized);
    }

    private RateLimitResult check(RateLimitPolicy policy, String identifier) {
        Instant now = clock.instant();
        AtomicReference<RateLimitResult> result = new AtomicReference<>();
        counters.asMap().compute(policy.name() + ':' + identifier, (key, existing) -> {
            Instant resetAt = existing == null ? now : existing.startedAt().plus(policy.window());
            if (existing == null || !now.isBefore(resetAt)) {
                result.set(RateLimitResult.permit());
                return new FixedWindow(now, 1);
            }
            if (existing.attempts() < policy.limit()) {
                result.set(RateLimitResult.permit());
                return new FixedWindow(existing.startedAt(), existing.attempts() + 1);
            }
            long remainingMillis = Math.max(1, Duration.between(now, resetAt).toMillis());
            long retryAfterSeconds = Math.max(1, (remainingMillis + 999) / 1_000);
            result.set(RateLimitResult.reject(retryAfterSeconds));
            return existing;
        });
        return result.get();
    }

    private static void requireAllowed(RateLimitResult result) {
        if (!result.allowed()) {
            throw new RateLimitException(result.retryAfterSeconds());
        }
    }

    long estimatedEntryCount() {
        counters.cleanUp();
        return counters.estimatedSize();
    }

    private record FixedWindow(Instant startedAt, int attempts) {
    }
}
