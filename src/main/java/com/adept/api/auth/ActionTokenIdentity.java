package com.adept.api.auth;

import java.util.UUID;

import com.adept.api.common.domain.ActionTokenPurpose;

public record ActionTokenIdentity(
    UUID tokenId,
    UUID userId,
    ActionTokenPurpose purpose
) {
}
