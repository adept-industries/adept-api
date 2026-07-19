package com.adept.api.user;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.common.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "users")
// One instance represents one row in the users table.
public class User extends BaseEntity {
    // No name= is needed because "email" already matches the SQL column.
    @Column(nullable = false, length = 320)
    private String email;

    // Explicit name maps Java camelCase to SQL snake_case.
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "avatar_url", columnDefinition = "text")
    private String avatarUrl;

    // Store the enum text (ACTIVE), not its numeric ordinal (0).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // Incrementing this invalidates previously issued access tokens.
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;
}
