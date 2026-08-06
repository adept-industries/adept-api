package com.adept.api.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.adept.api.common.domain.ActionTokenPurpose;

import jakarta.persistence.LockModeType;

public interface UserActionTokenRepository extends JpaRepository<UserActionToken, UUID> {

    @Query("""
        select new com.adept.api.auth.ActionTokenIdentity(t.id, t.user.id, t.purpose)
        from UserActionToken t
        where t.tokenHash = :tokenHash
        """)
    Optional<ActionTokenIdentity> findIdentityByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select t
        from UserActionToken t
        join fetch t.user
        where t.id = :id
        """)
    Optional<UserActionToken> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select t
        from UserActionToken t
        where t.user.id = :userId
          and t.purpose = :purpose
          and t.consumedAt is null
        order by t.id asc
        """)
    List<UserActionToken> findActiveByUserAndPurposeForUpdate(
        @Param("userId") UUID userId,
        @Param("purpose") ActionTokenPurpose purpose
    );
}
