package com.adept.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailRequest(
    @NotBlank @Email @Size(max = 320) String email
) {
    @Override
    public String toString() {
        return "EmailRequest[email=<redacted>]";
    }
}
