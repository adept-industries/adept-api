package com.adept.api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class ValidPasswordValidator implements ConstraintValidator<ValidPassword, String> {

    private final PasswordPolicy policy;

    public ValidPasswordValidator(PasswordPolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        var violation = policy.violation(value);
        if (violation.isEmpty()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(violation.get())
            .addConstraintViolation();
        return false;
    }
}
