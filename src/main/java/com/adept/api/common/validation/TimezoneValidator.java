package com.adept.api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;
import java.util.Set;

/**
 * Validates that a string value is a recognized IANA timezone identifier.
 *
 * <p>Uses the JVM's built-in {@link ZoneId#getAvailableZoneIds()} set, which
 * includes all IANA zone names supported by the current JDK. No external
 * dependency or network call is needed.
 */
public class TimezoneValidator implements ConstraintValidator<ValidTimezone, String> {

    // Loaded once at class initialization; the set does not change at runtime.
    private static final Set<String> VALID_ZONE_IDS = ZoneId.getAvailableZoneIds();

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Allow null; combine @ValidTimezone with @NotBlank when required.
        if (value == null) {
            return true;
        }
        return VALID_ZONE_IDS.contains(value);
    }
}
