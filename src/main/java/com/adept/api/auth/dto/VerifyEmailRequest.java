package com.adept.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/verify-email}.
 *
 * @param token raw one-time token from the verification email link.
 *              The service hashes it and compares against the stored HMAC.
 */
public record VerifyEmailRequest(
        @NotBlank(message = "token must not be blank")
        String token
) {}
