package com.adept.api.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.ProblemCode;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public final class JwtService {

    private static final String MEMBERSHIP_ID = "membershipId";
    private static final String WORKSPACE_ID = "workspaceId";
    private static final String ROLE = "role";
    private static final String TOKEN_VERSION = "tokenVersion";
    private static final String AUTHENTICATED_AT = "auth_time";
    private static final long MAX_FUTURE_ISSUED_AT_SECONDS = 30;

    private final AppProperties.Jwt properties;
    private final Clock clock;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtService(AppProperties appProperties, Clock clock) {
        this.properties = appProperties.jwt();
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(properties.secretBase64()));
        this.parser = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(properties.issuer())
            .requireAudience(properties.audience())
            .clock(() -> Date.from(clock.instant()))
            .build();
    }

    public String issue(AuthenticatedPrincipal principal) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        var builder = Jwts.builder()
            .issuer(properties.issuer())
            .subject(principal.userId().toString())
            .audience().add(properties.audience()).and()
            .claim(MEMBERSHIP_ID, principal.membershipId().toString())
            .claim(WORKSPACE_ID, principal.workspaceId().toString())
            .claim(ROLE, principal.role().name())
            .claim(TOKEN_VERSION, principal.tokenVersion())
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .id(UUID.randomUUID().toString());
        if (principal.authenticatedAt() != null) {
            builder.claim(AUTHENTICATED_AT, principal.authenticatedAt().getEpochSecond());
        }
        return builder
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }

    public JwtClaims parse(String compactToken) {
        try {
            if (compactToken == null || compactToken.isBlank()) {
                throw invalidSession();
            }
            Jws<Claims> signed = parser.parseSignedClaims(compactToken);
            if (!"HS256".equals(signed.getHeader().getAlgorithm())) {
                throw invalidSession();
            }
            Claims claims = signed.getPayload();
            Instant issuedAt = requiredDate(claims.getIssuedAt()).toInstant();
            Instant expiresAt = requiredDate(claims.getExpiration()).toInstant();
            if (issuedAt.isAfter(clock.instant().plusSeconds(MAX_FUTURE_ISSUED_AT_SECONDS))) {
                throw invalidSession();
            }
            Instant authenticatedAt = optionalInstant(claims.get(AUTHENTICATED_AT));
            if (authenticatedAt != null
                    && authenticatedAt.isAfter(clock.instant().plusSeconds(MAX_FUTURE_ISSUED_AT_SECONDS))) {
                throw invalidSession();
            }
            return new JwtClaims(
                requiredUuid(claims.getSubject()),
                requiredUuid(claims.get(MEMBERSHIP_ID, String.class)),
                requiredUuid(claims.get(WORKSPACE_ID, String.class)),
                MembershipRole.valueOf(requiredString(claims.get(ROLE, String.class))),
                requiredTokenVersion(claims.get(TOKEN_VERSION)),
                authenticatedAt,
                issuedAt,
                expiresAt,
                requiredUuid(claims.getId())
            );
        } catch (UnauthorizedException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException | ClassCastException exception) {
            throw invalidSession();
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    private static Date requiredDate(Date value) {
        if (value == null) {
            throw invalidSession();
        }
        return value;
    }

    private static String requiredString(String value) {
        if (value == null || value.isBlank()) {
            throw invalidSession();
        }
        return value;
    }

    private static UUID requiredUuid(String value) {
        return UUID.fromString(requiredString(value));
    }

    private static int requiredTokenVersion(Object value) {
        if (!(value instanceof Number number)) {
            throw invalidSession();
        }
        int tokenVersion = number.intValue();
        if (tokenVersion < 0 || number.doubleValue() != tokenVersion) {
            throw invalidSession();
        }
        return tokenVersion;
    }

    private static Instant optionalInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw invalidSession();
        }
        long epochSecond = number.longValue();
        if (number.doubleValue() != epochSecond) {
            throw invalidSession();
        }
        return Instant.ofEpochSecond(epochSecond);
    }

    private static UnauthorizedException invalidSession() {
        return new UnauthorizedException(ProblemCode.SESSION_INVALID);
    }
}
