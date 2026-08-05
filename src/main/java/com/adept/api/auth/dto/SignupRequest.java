package com.adept.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.adept.api.common.validation.ValidTimezone;

/**
 * Request body for {@code POST /api/v1/auth/signup}.
 *
 * <p>Never log this record; it contains the raw password.
 *
 * @param email         login identifier; normalized to lower-case by the service.
 * @param password      accepted only for immediate BCrypt hashing; never stored raw.
 * @param displayName   user-facing full name shown in the UI.
 * @param workspaceName name of the first tenant workspace created for this account.
 * @param timezone      IANA timezone name (e.g. {@code "Asia/Colombo"}).
 */
public record SignupRequest(
        @NotBlank @Email(message = "must be a valid email address")
        @Size(max = 320, message = "email must not exceed 320 characters")
        String email,

        @NotBlank(message = "password must not be blank")
        @Size(min = 12, max = 128, message = "password must be between 12 and 128 characters")
        String password,

        @NotBlank(message = "displayName must not be blank")
        @Size(max = 160, message = "displayName must not exceed 160 characters")
        String displayName,

        @NotBlank(message = "workspaceName must not be blank")
        @Size(max = 160, message = "workspaceName must not exceed 160 characters")
        String workspaceName,

        @NotBlank(message = "timezone must not be blank")
        @ValidTimezone
        String timezone
) {}
