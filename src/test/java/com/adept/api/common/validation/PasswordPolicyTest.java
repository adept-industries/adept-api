package com.adept.api.common.validation;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void loadsPinnedCommonPasswordList() {
        PasswordPolicy policy = new PasswordPolicy();

        assertThat(policy.commonPasswordCount()).isGreaterThanOrEqualTo(9_000);
        assertThat(policy.isValid("password")).isFalse();
    }

    @Test
    void enforcesCodePointAndUtf8ByteBoundaries() {
        PasswordPolicy policy = new PasswordPolicy(Set.of());

        assertThat(policy.isValid("abcdefghijk")).isFalse();
        assertThat(policy.isValid("abcdefghijkl")).isTrue();
        assertThat(policy.isValid("a".repeat(72))).isTrue();
        assertThat(policy.isValid("a".repeat(73))).isFalse();
        assertThat(policy.isValid("\uD83D\uDE00".repeat(12))).isTrue();
    }

    @Test
    void rejectsOnlyWholeCaseInsensitiveBlocklistMatches() {
        PasswordPolicy policy = new PasswordPolicy(Set.of("CommonPassword12"));

        assertThat(policy.isValid("commonpassword12")).isFalse();
        assertThat(policy.isValid("prefixCommonPassword12suffix")).isTrue();
    }
}
