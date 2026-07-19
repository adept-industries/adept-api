package com.adept.api.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    boolean existsByEmailIgnoreCase(String email);
}
