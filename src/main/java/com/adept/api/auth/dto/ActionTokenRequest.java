package com.adept.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ActionTokenRequest(
    @NotBlank String token
) {
    @Override
    public String toString() {
        return "ActionTokenRequest[token=<redacted>]";
    }
}
