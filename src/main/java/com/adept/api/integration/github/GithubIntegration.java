package com.adept.api.integration.github;

import com.adept.api.common.domain.*;
import com.adept.api.workspace.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "github_integrations")
// Represents one GitHub App installation connected to a workspace.
public class GithubIntegration extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(name = "installation_id", nullable = false, unique = true)
    private long installationId;

    @Column(name = "account_external_id", nullable = false)
    private long accountExternalId;

    @Column(name = "account_login", nullable = false)
    private String accountLogin;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private GithubAccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "repository_selection", nullable = false, length = 16)
    private RepositorySelection repositorySelection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IntegrationStatus status = IntegrationStatus.ACTIVE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> permissions = Map.of();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installed_by_membership_id")
    private Membership installedBy;

    @Column(name = "installed_at", nullable = false) private Instant installedAt;
    @Column(name = "last_synced_at") private Instant lastSyncedAt;
    @Column(name = "suspended_at") private Instant suspendedAt;
}
