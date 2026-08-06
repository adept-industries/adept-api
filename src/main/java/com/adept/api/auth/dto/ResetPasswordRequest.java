package com.adept.api.auth.dto;

import com.adept.api.common.validation.ValidPassword;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
    @NotBlank String token,
    @NotBlank @ValidPassword String newPassword
) {
    @Override
    public String toString() {
        return "ResetPasswordRequest[token=<redacted>, newPassword=<redacted>]";
    }
}
