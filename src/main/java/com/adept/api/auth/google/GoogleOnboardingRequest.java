package com.adept.api.auth.google;

import com.adept.api.common.validation.ValidTimezone;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleOnboardingRequest(
    @NotBlank @Size(max = 160) String workspaceName,
    @NotBlank @ValidTimezone String timezone
) {
}

