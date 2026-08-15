package com.adept.api.integration.common;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface IntegrationOauthStateRepository extends JpaRepository<IntegrationOauthState, UUID> {

    Optional<IntegrationOauthState> findByStateHash(String stateHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select s from IntegrationOauthState s
        where s.stateHash = :stateHash
          and s.consumedAt is null
          and s.expiresAt > :now
        """)
    Optional<IntegrationOauthState> findActiveByStateHashForUpdate(
        @Param("stateHash") String stateHash,
        @Param("now") Instant now
    );
}
