package com.adept.api.integration.jira;

import com.adept.api.common.domain.*;
import com.adept.api.workspace.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "jira_integrations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "cloud_id"}))
// Stores one Jira Cloud connection and encrypted rotating credentials.
public class JiraIntegration extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(name = "cloud_id", nullable = false) private String cloudId;
    @Column(name = "site_url", nullable = false, columnDefinition = "text") private String siteUrl;
    @Column(name = "display_name", nullable = false) private String displayName;
    @Column(name = "access_token_enc", nullable = false, columnDefinition = "text") private String accessTokenEnc;
    @Column(name = "refresh_token_enc", nullable = false, columnDefinition = "text") private String refreshTokenEnc;
    @Column(name = "encryption_key_version", nullable = false) private int encryptionKeyVersion;
    @Column(name = "access_token_expires_at", nullable = false) private Instant accessTokenExpiresAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] scopes = new String[0];

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IntegrationStatus status = IntegrationStatus.ACTIVE;

    @Column(name = "webhook_id") private Long webhookId;
    @Column(name = "webhook_expires_at") private Instant webhookExpiresAt;
    @Column(name = "last_synced_at") private Instant lastSyncedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connected_by_membership_id")
    private Membership connectedBy;
}
