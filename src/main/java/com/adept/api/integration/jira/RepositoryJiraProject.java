package com.adept.api.integration.jira;

import com.adept.api.integration.github.GitRepository;
import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
// Composite key contains the two foreign-key columns of this join table.
class RepositoryJiraProjectId implements Serializable {
    @Column(name = "repository_id") private UUID repositoryId;
    @Column(name = "jira_project_id") private UUID jiraProjectId;
}

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "repository_jira_projects")
// Join entity maps repositories and Jira projects many-to-many.
public class RepositoryJiraProject {
    @EmbeddedId
    private RepositoryJiraProjectId id;

    @MapsId("repositoryId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private GitRepository repository;

    @MapsId("jiraProjectId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "jira_project_id", nullable = false)
    private JiraProject jiraProject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
