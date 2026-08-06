package com.adept.api.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Modifying
    @Query("""
        update RefreshToken r
        set r.revokedAt = :revokedAt
        where r.user.id = :userId
          and r.revokedAt is null
        """)
    int revokeActiveByUserId(
        @Param("userId") UUID userId,
        @Param("revokedAt") Instant revokedAt
    );
}
