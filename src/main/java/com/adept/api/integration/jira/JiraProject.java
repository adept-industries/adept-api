package com.adept.api.integration.jira;

import com.adept.api.common.domain.BaseEntity;
import com.adept.api.workspace.Workspace;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "jira_projects")
// Local representation of a project discovered from one Jira integration.
public class JiraProject extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jira_integration_id", nullable = false)
    private JiraIntegration jiraIntegration;

    @Column(name = "jira_project_id", nullable = false) private String jiraProjectId;
    @Column(name = "project_key", nullable = false, length = 64) private String projectKey;
    @Column(name = "project_name", nullable = false) private String projectName;
    @Column(name = "project_type", length = 64) private String projectType;
    @Column(name = "tracking_enabled", nullable = false) private boolean trackingEnabled;
    @Column(name = "last_synced_at") private Instant lastSyncedAt;
}
