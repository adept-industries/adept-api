package com.adept.api.invitation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
    @NotBlank(message = "Invitation token is required.")
    String token,

    @Size(max = 100, message = "Display name cannot exceed 100 characters.")
    String displayName,

    String password
) {
}
