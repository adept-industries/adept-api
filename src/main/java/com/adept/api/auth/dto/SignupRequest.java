package com.adept.api.auth.dto;

import com.adept.api.common.validation.ValidPassword;
import com.adept.api.common.validation.ValidTimezone;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @ValidPassword String password,
    @NotBlank @Size(max = 160) String displayName,
    @NotBlank @Size(max = 160) String workspaceName,
    @NotBlank @ValidTimezone String timezone
) {
    @Override
    public String toString() {
        return "SignupRequest[email=<redacted>, password=<redacted>, displayName=<redacted>, "
            + "workspaceName=<redacted>, timezone=" + timezone + "]";
    }
}
