package com.adept.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/forgot-password}.
 *
 * <p>The endpoint always returns {@code 202 Accepted} regardless of whether the
 * email exists to prevent account-discovery.
 *
 * @param email address to send the password-reset link to.
 */
public record ForgotPasswordRequest(
        @NotBlank @Email(message = "must be a valid email address")
        @Size(max = 320)
        String email
) {}
