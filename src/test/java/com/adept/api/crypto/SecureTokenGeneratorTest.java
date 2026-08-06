package com.adept.api.crypto;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecureTokenGeneratorTest {

    @Test
    void generatesUniqueThirtyTwoByteBase64UrlTokensWithoutPadding() {
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
    }

    @Test
    void rejectsWrongTokenTransportShapes() {
        assertThat(SecureTokenGenerator.isWellFormed(null)).isFalse();
        assertThat(SecureTokenGenerator.isWellFormed("abc")).isFalse();
        assertThat(SecureTokenGenerator.isWellFormed("A".repeat(42) + "=")).isFalse();
    }
}
