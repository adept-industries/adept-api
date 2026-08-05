package com.adept.api.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that the annotated {@code String} is a recognized IANA timezone
 * identifier (e.g. {@code "Asia/Colombo"}, {@code "UTC"}).
 *
 * <p>Null values are considered valid by this constraint; combine with
 * {@code @NotBlank} when the field is required.
 */
@Documented
@Constraint(validatedBy = TimezoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimezone {

    String message() default "must be a valid IANA timezone identifier";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
