package com.adept.api.integration.common;

import com.adept.api.common.domain.ExternalProvider;
import com.adept.api.workspace.*;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "integration_oauth_states")
// Short-lived OAuth request state; consumed callbacks cannot be replayed.
public class IntegrationOauthState {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExternalProvider provider;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by_membership_id", nullable = false)
    private Membership initiatedBy;

    @Column(name = "state_hash", nullable = false, unique = true, length = 128)
    private String stateHash;

    @Column(name = "code_verifier_enc", columnDefinition = "text")
    private String codeVerifierEnc;

    @Column(name = "redirect_path", nullable = false, length = 500)
    private String redirectPath = "/dashboard/integrations";

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
