package com.adept.api.auth.dto;

import jakarta.validation.constraints.NotNull;

public record PasswordReauthenticationRequest(
    @NotNull String password
) {
    @Override
    public String toString() {
        return "PasswordReauthenticationRequest[password=<redacted>]";
    }
}

