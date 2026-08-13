package com.adept.api.auth.google;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface GoogleAuthAccountRepository extends JpaRepository<GoogleAuthAccount, UUID> {

    @Query("select account.user.id from GoogleAuthAccount account where account.googleSubject = :subject")
    Optional<UUID> findUserIdByGoogleSubject(@Param("subject") String subject);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from GoogleAuthAccount account where account.googleSubject = :subject")
    Optional<GoogleAuthAccount> findByGoogleSubjectForUpdate(@Param("subject") String subject);

    boolean existsByGoogleSubject(String subject);
}

