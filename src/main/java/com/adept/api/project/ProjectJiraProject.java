package com.adept.api.project;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import com.adept.api.integration.jira.JiraProject;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
class ProjectJiraProjectId implements Serializable {
    @Column(name = "project_id") private UUID projectId;
    @Column(name = "jira_project_id") private UUID jiraProjectId;
}

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "project_jira_projects")
public class ProjectJiraProject {
    @EmbeddedId private ProjectJiraProjectId id;

    @MapsId("projectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @MapsId("jiraProjectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jira_project_id", nullable = false)
    private JiraProject jiraProject;

    @Column(name = "workspace_id", nullable = false) private UUID workspaceId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    public static ProjectJiraProject create(Project project, JiraProject jiraProject, Instant createdAt) {
        ProjectJiraProject link = new ProjectJiraProject();
        link.setId(new ProjectJiraProjectId(project.getId(), jiraProject.getId()));
        link.setProject(project);
        link.setJiraProject(jiraProject);
        link.setWorkspaceId(project.getWorkspace().getId());
        link.setCreatedAt(createdAt);
        return link;
    }
}
