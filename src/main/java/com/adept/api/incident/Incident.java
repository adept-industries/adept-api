package com.adept.api.incident;

import com.adept.api.common.domain.*;
import com.adept.api.deployment.Deployment;
import com.adept.api.integration.github.GitRepository;
import com.adept.api.integration.jira.JiraIssue;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "incidents")
// Operational failure and recovery record used by DORA metrics.
public class Incident extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "workspace_id", nullable = false) private Workspace workspace;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "repository_id") private GitRepository repository;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "jira_issue_id") private JiraIssue jiraIssue;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private IncidentSource source;
    @Column(nullable = false, columnDefinition = "text") private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private IncidentSeverity severity = IncidentSeverity.UNKNOWN;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private IncidentStatus status;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "failed_deployment_id") private Deployment failedDeployment;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "recovery_deployment_id") private Deployment recoveryDeployment;
    @Column(name = "detected_at", nullable = false) private Instant detectedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
}
