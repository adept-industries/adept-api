package com.adept.api.invitation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRepositoryLeadInvitationRequest(
    @NotBlank @Email @Size(max = 320) String email
) {
    @Override
    public String toString() {
        return "CreateRepositoryLeadInvitationRequest[email=<redacted>]";
    }
}
