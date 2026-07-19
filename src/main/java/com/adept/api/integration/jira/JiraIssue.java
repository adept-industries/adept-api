package com.adept.api.integration.jira;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "jira_issues")
// Query-friendly Jira issue fields plus the original provider JSON.
public class JiraIssue extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "jira_project_id", nullable = false) private JiraProject jiraProject;
    @Column(name = "jira_issue_id", nullable = false) private String jiraIssueId;
    @Column(name = "issue_key", nullable = false, length = 64) private String issueKey;
    @Column(name = "issue_type", length = 128) private String issueType;
    @Column(name = "status_name", length = 128) private String statusName;
    @Column(name = "priority_name", length = 128) private String priorityName;
    @Column(nullable = false, columnDefinition = "text") private String summary;
    @Column(name = "is_incident", nullable = false) private boolean incident;
    @Column(name = "jira_created_at") private Instant jiraCreatedAt;
    @Column(name = "jira_updated_at") private Instant jiraUpdatedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_data", nullable = false, columnDefinition = "jsonb") private Map<String,Object> rawData = Map.of();
}
