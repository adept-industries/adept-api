package com.adept.api.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    private final TokenHasher hasher = new TokenHasher(new byte[32]);

    @Test
    void returnsDeterministicLowercaseSha256Hex() {
        String first = hasher.hashRefreshToken("raw-token");

        assertThat(first).matches("^[0-9a-f]{64}$");
        assertThat(hasher.hashRefreshToken("raw-token")).isEqualTo(first);
        assertThat(hasher.hashRefreshToken("another-token")).isNotEqualTo(first);
    }

    @Test
    void separatesEveryHashDomain() {
        String value = "same-value";

        assertThat(java.util.Arrays.stream(TokenHasher.Domain.values())
            .map(domain -> hasher.hash(domain, value)))
            .doesNotHaveDuplicates();
    }
}
