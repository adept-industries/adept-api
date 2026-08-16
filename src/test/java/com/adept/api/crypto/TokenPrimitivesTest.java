package com.adept.api.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TokenPrimitivesTest {

    @Test
    void randomTokensAreWellFormedAndHashesAreDeterministicAndDomainSeparated() {
        SecureTokenGenerator generator = new SecureTokenGenerator();
        Set<String> generated = new HashSet<>();

        for (int index = 0; index < 500; index++) {
            String token = generator.generate();
            assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            assertThat(SecureTokenGenerator.isWellFormed(token)).isTrue();
            generated.add(token);
        }
        assertThat(generated).hasSize(500);
        assertThat(SecureTokenGenerator.isWellFormed(null)).isFalse();
        assertThat(SecureTokenGenerator.isWellFormed("abc")).isFalse();
        assertThat(SecureTokenGenerator.isWellFormed("A".repeat(42) + "=")).isFalse();

        TokenHasher hasher = new TokenHasher(new byte[32]);
        String first = hasher.hashRefreshToken("raw-token");

        assertThat(first).matches("^[0-9a-f]{64}$");
        assertThat(hasher.hashRefreshToken("raw-token")).isEqualTo(first);
        assertThat(hasher.hashRefreshToken("another-token")).isNotEqualTo(first);
        assertThat(hasher.hashInvitationToken("raw-token")).isNotEqualTo(first);
        assertThat(Arrays.stream(TokenHasher.Domain.values())
            .map(domain -> hasher.hash(domain, "same-value")))
            .doesNotHaveDuplicates();
    }
}
