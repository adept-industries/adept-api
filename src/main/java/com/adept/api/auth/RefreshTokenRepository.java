package com.adept.api.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Query("SELECT r.id as id, r.user.id as userId FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    Optional<RefreshTokenProjection> findIdAndUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.id = :id")
    Optional<RefreshToken> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = CURRENT_TIMESTAMP WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = CURRENT_TIMESTAMP WHERE r.user.id = :userId AND r.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId);

    List<RefreshToken> findAllByFamilyId(UUID familyId);

    boolean existsByFamilyIdAndReuseDetectedAtIsNotNull(UUID familyId);
}
