package com.adept.api.integration.github;

import java.time.Instant;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.common.domain.IssueState;
import com.adept.api.workspace.Workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "github_issues")
public class GithubIssue extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private GitRepository repository;

    @Column(name = "github_issue_id", nullable = false)
    private long githubIssueId;

    @Column(name = "github_node_id")
    private String githubNodeId;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IssueState state = IssueState.OPEN;

    @Column(name = "author_login")
    private String authorLogin;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "assignee_logins", nullable = false, columnDefinition = "text[]")
    private String[] assigneeLogins = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] labels = new String[0];

    @Column(name = "comments_count", nullable = false)
    private int commentsCount;

    @Column(name = "github_created_at", nullable = false)
    private Instant githubCreatedAt;

    @Column(name = "github_updated_at")
    private Instant githubUpdatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt = Instant.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawData = Map.of();
}
