package com.adept.api.common.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidTimezoneValidatorTest {

    private final ValidTimezoneValidator validator = new ValidTimezoneValidator();

    @Test
    void acceptsZoneIdAndRejectsBlankUnknownAndOversizedValues() {
        assertThat(validator.isValid("Asia/Colombo", null)).isTrue();
        assertThat(validator.isValid("UTC", null)).isTrue();
        assertThat(validator.isValid("", null)).isFalse();
        assertThat(validator.isValid("Not/A_Timezone", null)).isFalse();
        assertThat(validator.isValid("x".repeat(65), null)).isFalse();
    }
}
