-- One row is one Jira Cloud site connected to a workspace.
CREATE TABLE jira_integrations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    cloud_id            VARCHAR(255) NOT NULL,
    site_url            TEXT NOT NULL,
    display_name        VARCHAR(255) NOT NULL,
    access_token_enc    TEXT NOT NULL,
    refresh_token_enc   TEXT NOT NULL,
    encryption_key_version INTEGER NOT NULL CHECK (encryption_key_version > 0),
    access_token_expires_at TIMESTAMPTZ NOT NULL,
    scopes              TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED', 'ERROR')),
    webhook_id          BIGINT,
    webhook_expires_at  TIMESTAMPTZ,
    last_synced_at      TIMESTAMPTZ,
    connected_by_membership_id UUID REFERENCES memberships(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (workspace_id, cloud_id)
);

-- Local catalog of projects discovered through a Jira integration.
CREATE TABLE jira_projects (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    jira_integration_id UUID NOT NULL REFERENCES jira_integrations(id) ON DELETE CASCADE,
    jira_project_id     VARCHAR(255) NOT NULL,
    project_key         VARCHAR(64) NOT NULL,
    project_name        VARCHAR(255) NOT NULL,
    project_type        VARCHAR(64),
    tracking_enabled    BOOLEAN NOT NULL DEFAULT false,
    last_synced_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (jira_integration_id, jira_project_id),
    UNIQUE (workspace_id, project_key)
);

CREATE INDEX idx_jira_projects_workspace_tracking
    ON jira_projects(workspace_id, tracking_enabled);

-- Join table: a repository can map to several Jira projects and vice versa.
CREATE TABLE repository_jira_projects (
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    jira_project_id     UUID NOT NULL REFERENCES jira_projects(id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (repository_id, jira_project_id)
);