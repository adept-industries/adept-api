package com.adept.api.auth;

import com.adept.api.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "refresh_tokens")
// Stores only a refresh-token hash plus rotation-family security metadata.
public class RefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_token_id")
    private RefreshToken parentToken;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "rotated_at") private Instant rotatedAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "reuse_detected_at") private Instant reuseDetectedAt;
    @Column(name = "user_agent_hash", length = 128) private String userAgentHash;
    @Column(name = "ip_hash", length = 128) private String ipHash;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
