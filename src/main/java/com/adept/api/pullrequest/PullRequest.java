package com.adept.api.pullrequest;

import com.adept.api.common.domain.*;
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
@Table(name = "pull_requests")
// Normalized current state and delivery timestamps for one GitHub PR.
public class PullRequest extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "repository_id", nullable = false) private GitRepository repository;
    @Column(name = "github_pr_id", nullable = false) private long githubPrId;
    @Column(name = "github_node_id") private String githubNodeId;
    @Column(nullable = false) private int number;
    @Column(nullable = false, columnDefinition = "text") private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private PullRequestState state;
    @Column(nullable = false) private boolean draft;
    @Column(name = "author_login") private String authorLogin;
    @Column(name = "base_ref", nullable = false) private String baseRef;
    @Column(name = "head_ref", nullable = false) private String headRef;
    @Column(name = "head_sha", length = 64) private String headSha;
    @Column(name = "merge_commit_sha", length = 64) private String mergeCommitSha;
    @Column(nullable = false) private int additions;
    @Column(nullable = false) private int deletions;
    @Column(name = "changed_files", nullable = false) private int changedFiles;
    @Column(name = "commit_count", nullable = false) private int commitCount;
    @Column(name = "opened_at", nullable = false) private Instant openedAt;
    @Column(name = "first_commit_at") private Instant firstCommitAt;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "merged_at") private Instant mergedAt;
    @Column(name = "last_synced_at", nullable = false) private Instant lastSyncedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_data", nullable = false, columnDefinition = "jsonb") private Map<String,Object> rawData = Map.of();
}
