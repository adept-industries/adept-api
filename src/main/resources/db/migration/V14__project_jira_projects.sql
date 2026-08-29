-- Jira projects describe work for an Adept project, not for one code repository.
-- Keep repository_jira_projects during the rolling frontend/API transition.
ALTER TABLE jira_projects
    ADD CONSTRAINT uq_jira_projects_id_workspace UNIQUE (id, workspace_id);

CREATE TABLE project_jira_projects (
    project_id      UUID NOT NULL,
    jira_project_id UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, jira_project_id),
    CONSTRAINT fk_project_jira_projects_project_workspace
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects(id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT fk_project_jira_projects_jira_workspace
        FOREIGN KEY (jira_project_id, workspace_id)
        REFERENCES jira_projects(id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX idx_project_jira_projects_workspace_project
    ON project_jira_projects(workspace_id, project_id);

-- Preserve existing intent. A repository can belong to multiple Adept projects,
-- so its tracked Jira mappings are copied to every project containing it.
INSERT INTO project_jira_projects (project_id, jira_project_id, workspace_id, created_at)
SELECT DISTINCT
    project_repository.project_id,
    repository_jira.jira_project_id,
    project_repository.workspace_id,
    repository_jira.created_at
FROM project_repositories project_repository
JOIN repository_jira_projects repository_jira
  ON repository_jira.repository_id = project_repository.repository_id
JOIN jira_projects jira_project
  ON jira_project.id = repository_jira.jira_project_id
 AND jira_project.workspace_id = project_repository.workspace_id
WHERE jira_project.tracking_enabled = true
ON CONFLICT (project_id, jira_project_id) DO NOTHING;
