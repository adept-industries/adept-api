package com.adept.api.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("""
        select u
        from User u
        where lower(u.email) = lower(:email)
        """)
    Optional<User> findByEmailIgnoreCase(
        @Param("email") String email
    );

    @Query("select u.id from User u where lower(u.email) = lower(:email)")
    Optional<UUID> findIdByEmailIgnoreCase(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select u
        from User u
        where u.id = :id
        """)
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select u
        from User u
        where lower(u.email) = lower(:email)
        """)
    Optional<User> findByEmailIgnoreCaseForUpdate(@Param("email") String email);

    boolean existsByEmailIgnoreCase(String email);
}
