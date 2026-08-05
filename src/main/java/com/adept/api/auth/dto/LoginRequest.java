package com.adept.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/login}.
 *
 * <p>Never log this record; it contains the raw password.
 *
 * @param email    login identifier; looked up case-insensitively.
 * @param password raw value compared against the stored BCrypt hash.
 */
public record LoginRequest(
        @NotBlank @Email(message = "must be a valid email address")
        @Size(max = 320)
        String email,

        @NotBlank(message = "password must not be blank")
        @Size(min = 1, max = 128)
        String password
) {}
