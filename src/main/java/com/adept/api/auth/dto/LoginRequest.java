package com.adept.api.auth.dto;

import jakarta.validation.constraints.NotNull;

public record LoginRequest(
    @NotNull String email,
    @NotNull String password
) {
    @Override
    public String toString() {
        return "LoginRequest[email=<redacted>, password=<redacted>]";
    }
}
