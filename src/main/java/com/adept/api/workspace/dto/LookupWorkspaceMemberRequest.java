package com.adept.api.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LookupWorkspaceMemberRequest(
    @NotBlank @Email @Size(max = 320) String email
) {
    @Override
    public String toString() {
        return "LookupWorkspaceMemberRequest[email=<redacted>]";
    }
}
