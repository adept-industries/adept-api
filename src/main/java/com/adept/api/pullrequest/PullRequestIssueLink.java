package com.adept.api.pullrequest;

import com.adept.api.common.domain.IssueLinkSource;
import com.adept.api.integration.jira.JiraIssue;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
// Composite key uniquely identifies one PR-to-Jira-issue link.
class PullRequestIssueLinkId implements Serializable {
    @Column(name = "pull_request_id") private UUID pullRequestId;
    @Column(name = "jira_issue_id") private UUID jiraIssueId;
}

@Getter @Setter @NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "pull_request_issue_links")
// Join entity records the inferred/manual issue link and its confidence.
public class PullRequestIssueLink {
    @EmbeddedId private PullRequestIssueLinkId id;
    @MapsId("pullRequestId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "pull_request_id", nullable = false) private PullRequest pullRequest;
    @MapsId("jiraIssueId") @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "jira_issue_id", nullable = false) private JiraIssue jiraIssue;
    @Enumerated(EnumType.STRING) @Column(name = "link_source", nullable = false, length = 32) private IssueLinkSource linkSource;
    @Column(nullable = false) private double confidence = 1.0;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
}
