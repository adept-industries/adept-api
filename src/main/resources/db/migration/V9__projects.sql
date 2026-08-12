-- Projects group one or more repositories inside a workspace. The workspace
-- remains the tenant and authorization boundary; a project is a dashboard and
-- reporting filter within that boundary.
CREATE TABLE projects (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id                UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name                        VARCHAR(160) NOT NULL,
    description                 VARCHAR(1000),
    created_by_membership_id    UUID REFERENCES memberships(id) ON DELETE SET NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                     BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, workspace_id)
);

CREATE UNIQUE INDEX uq_projects_workspace_name_ci
    ON projects(workspace_id, lower(name));

-- The redundant workspace_id lets PostgreSQL enforce that both sides of a
-- project/repository association belong to the same tenant.
ALTER TABLE repositories
    ADD CONSTRAINT uq_repositories_id_workspace UNIQUE (id, workspace_id);

CREATE TABLE project_repositories (
    project_id      UUID NOT NULL,
    repository_id   UUID NOT NULL,
    workspace_id    UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, repository_id),
    CONSTRAINT fk_project_repositories_project_workspace
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects(id, workspace_id) ON DELETE CASCADE,
    CONSTRAINT fk_project_repositories_repository_workspace
        FOREIGN KEY (repository_id, workspace_id)
        REFERENCES repositories(id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX idx_project_repositories_workspace_project
    ON project_repositories(workspace_id, project_id);
CREATE INDEX idx_project_repositories_workspace_repository
    ON project_repositories(workspace_id, repository_id);
