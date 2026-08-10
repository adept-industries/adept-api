package com.adept.api.common.validation;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void enforcesTheCreationPolicyAndPinnedBlocklist() {
        PasswordPolicy policy = new PasswordPolicy();

        assertThat(policy.commonPasswordCount()).isGreaterThanOrEqualTo(9_000);
        assertThat(policy.isValid("password")).isFalse();

        PasswordPolicy boundaryPolicy = new PasswordPolicy(Set.of());

        assertThat(boundaryPolicy.isValid("abcdefghijk")).isFalse();
        assertThat(boundaryPolicy.isValid("abcdefghijkl")).isTrue();
        assertThat(boundaryPolicy.isValid("a".repeat(72))).isTrue();
        assertThat(boundaryPolicy.isValid("a".repeat(73))).isFalse();
        assertThat(boundaryPolicy.isValid("\uD83D\uDE00".repeat(12))).isTrue();

        PasswordPolicy exactMatchPolicy = new PasswordPolicy(Set.of("CommonPassword12"));

        assertThat(exactMatchPolicy.isValid("commonpassword12")).isFalse();
        assertThat(exactMatchPolicy.isValid("prefixCommonPassword12suffix")).isTrue();
    }
}
