package com.adept.api.pullrequest;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "pull_request_features")
// Versioned model inputs extracted from a particular pull request.
public class PullRequestFeature extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private GitRepository repository;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pull_request_id", nullable = false) private PullRequest pullRequest;
    @Column(name = "feature_schema_version", nullable = false, length = 64) private String featureSchemaVersion;
    @Column(name = "lines_added", nullable = false) private int linesAdded;
    @Column(name = "lines_deleted", nullable = false) private int linesDeleted;
    @Column(name = "files_changed", nullable = false) private int filesChanged;
    @Column(name = "commit_count", nullable = false) private int commitCount;
    @Column(name = "author_prior_pr_count", nullable = false) private int authorPriorPrCount;
    @Column(name = "author_prior_merge_rate") private Double authorPriorMergeRate;
    @Column(name = "test_file_ratio") private Double testFileRatio;
    private Double entropy;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "feature_payload", nullable = false, columnDefinition = "jsonb") private Map<String,Object> featurePayload = Map.of();
    @Column(name = "extracted_at", nullable = false) private Instant extractedAt = Instant.now();
}
