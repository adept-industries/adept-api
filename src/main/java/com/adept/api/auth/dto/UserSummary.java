package com.adept.api.auth.dto;

import java.util.UUID;

import com.adept.api.user.User;

public record UserSummary(
    UUID id,
    String email,
    String displayName,
    boolean emailVerified,
    boolean hasPassword
) {
    public static UserSummary from(User user) {
        return new UserSummary(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getEmailVerifiedAt() != null,
            user.getPasswordHash() != null && !user.getPasswordHash().isBlank()
        );
    }
}
