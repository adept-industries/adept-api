package com.adept.api.integration.github;

import com.adept.api.common.domain.*;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "repositories",
       uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "github_repo_id"}))
// Local, workspace-scoped representation of a GitHub repository.
public class GitRepository extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "github_integration_id", nullable = false)
    private GithubIntegration githubIntegration;

    @Column(name = "github_repo_id", nullable = false) private long githubRepoId;
    @Column(name = "github_node_id") private String githubNodeId;
    @Column(name = "owner_login", nullable = false) private String ownerLogin;
    @Column(nullable = false) private String name;
    @Column(name = "full_name", nullable = false, length = 512) private String fullName;
    @Column(name = "default_branch", nullable = false) private String defaultBranch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RepositoryVisibility visibility;

    @Column(nullable = false) private boolean archived;
    @Column(name = "tracking_enabled", nullable = false) private boolean trackingEnabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings = Map.of();

    @Column(name = "last_synced_at") private Instant lastSyncedAt;
}
