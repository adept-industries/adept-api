package com.adept.api.auth.google;

import java.time.Instant;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "google_auth_accounts")
public class GoogleAuthAccount extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "google_subject", nullable = false, unique = true, length = 255)
    private String googleSubject;

    @Column(name = "google_email", nullable = false, length = 320)
    private String googleEmail;

    @Column(name = "last_authenticated_at", nullable = false)
    private Instant lastAuthenticatedAt;
}

