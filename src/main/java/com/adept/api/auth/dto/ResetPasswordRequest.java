package com.adept.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/reset-password}.
 *
 * <p>Never log this record; it contains the raw password.
 *
 * @param token       raw one-time reset token from the password-reset email.
 * @param newPassword new password to hash and store; accepted only for immediate hashing.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "token must not be blank")
        String token,

        @NotBlank(message = "newPassword must not be blank")
        @Size(min = 12, max = 128, message = "newPassword must be between 12 and 128 characters")
        String newPassword
) {}
