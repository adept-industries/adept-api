package com.adept.api.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.adept.api.common.domain.MembershipRole;
import com.adept.api.common.error.UnauthorizedException;
import com.adept.api.config.AppProperties;
import com.adept.api.support.TestAppProperties;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID WORKSPACE_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    private AppProperties properties;
    private Clock clock;
    private JwtService service;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        properties = TestAppProperties.create();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new JwtService(properties, clock);
        key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(properties.jwt().secretBase64()));
    }

    @Test
    void issuesAndParsesExactAccessTokenClaims() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            USER_ID,
            MEMBERSHIP_ID,
            WORKSPACE_ID,
            MembershipRole.MANAGER,
            7,
            NOW.minusSeconds(30)
        );

        JwtClaims claims = service.parse(service.issue(principal));

        assertThat(claims.principal()).isEqualTo(principal);
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(claims.jwtId()).isNotNull();
        assertThat(service.accessTokenTtlSeconds()).isEqualTo(900);
    }

    @Test
    void rejectsTamperedExpiredFutureAndContractInvalidTokens() {
        String valid = token(builderWithRequiredClaims(), Jwts.SIG.HS256);
        String[] parts = valid.split("\\.");
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + '.' + parts[1] + '.' + replacement + parts[2].substring(1);

        assertInvalid(tampered);
        assertInvalid(token(builderWithRequiredClaims()
            .issuedAt(Date.from(NOW.minusSeconds(120)))
            .expiration(Date.from(NOW.minusSeconds(60))), Jwts.SIG.HS256));
        assertInvalid(token(builderWithRequiredClaims()
            .issuedAt(Date.from(NOW.plusSeconds(31)))
            .expiration(Date.from(NOW.plusSeconds(931))), Jwts.SIG.HS256));
        assertInvalid(token(builderWithRequiredClaims()
            .claim("auth_time", NOW.plusSeconds(31).getEpochSecond()), Jwts.SIG.HS256));
        assertInvalid(token(builderWithRequiredClaims().issuer("wrong-issuer"), Jwts.SIG.HS256));
        assertInvalid(token(builderWithRequiredClaims().claim("aud", java.util.Set.of("wrong-audience")), Jwts.SIG.HS256));
        assertInvalid(token(builderWithRequiredClaims().claim("membershipId", "not-a-uuid"), Jwts.SIG.HS256));
        assertInvalid(token(builderWithRequiredClaims().claim("role", null), Jwts.SIG.HS256));
        assertInvalid(token(builderWithRequiredClaims(), Jwts.SIG.HS384));
    }

    private JwtBuilder builderWithRequiredClaims() {
        return Jwts.builder()
            .issuer(properties.jwt().issuer())
            .subject(USER_ID.toString())
            .audience().add(properties.jwt().audience()).and()
            .claim("membershipId", MEMBERSHIP_ID.toString())
            .claim("workspaceId", WORKSPACE_ID.toString())
            .claim("role", MembershipRole.MANAGER.name())
            .claim("tokenVersion", 7)
            .issuedAt(Date.from(NOW))
            .expiration(Date.from(NOW.plusSeconds(900)))
            .id(UUID.randomUUID().toString());
    }

    private String token(JwtBuilder builder, io.jsonwebtoken.security.SecureDigestAlgorithm<SecretKey, ?> algorithm) {
        return builder.signWith(key, algorithm).compact();
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> service.parse(token))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessageNotContaining(token);
    }
}
