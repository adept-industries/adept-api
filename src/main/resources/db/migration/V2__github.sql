-- One row is one GitHub App installation connected to an Adept workspace.
CREATE TABLE github_integrations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    installation_id     BIGINT NOT NULL,
    account_external_id BIGINT NOT NULL,
    account_login       VARCHAR(255) NOT NULL,
    account_type        VARCHAR(32) NOT NULL
                        CHECK (account_type IN ('ORGANIZATION', 'USER')),
    repository_selection VARCHAR(16) NOT NULL
                        CHECK (repository_selection IN ('ALL', 'SELECTED')),
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED', 'ERROR')),
    permissions         JSONB NOT NULL DEFAULT '{}'::jsonb,
    installed_by_membership_id UUID REFERENCES memberships(id) ON DELETE SET NULL,
    installed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_synced_at      TIMESTAMPTZ,
    suspended_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (installation_id),
    UNIQUE (workspace_id, installation_id)
);

CREATE INDEX idx_github_integrations_workspace_status
    ON github_integrations(workspace_id, status);

-- Local catalog of GitHub repositories available to a workspace.
CREATE TABLE repositories (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    github_integration_id UUID NOT NULL REFERENCES github_integrations(id) ON DELETE CASCADE,
    github_repo_id      BIGINT NOT NULL,
    github_node_id      VARCHAR(255),
    owner_login         VARCHAR(255) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    full_name           VARCHAR(512) NOT NULL,
    default_branch      VARCHAR(255) NOT NULL DEFAULT 'main',
    visibility          VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'
                        CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'INTERNAL')),
    archived            BOOLEAN NOT NULL DEFAULT false,
    tracking_enabled    BOOLEAN NOT NULL DEFAULT false,
    settings            JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_synced_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (workspace_id, github_repo_id),
    UNIQUE (github_integration_id, github_repo_id)
);

CREATE INDEX idx_repositories_workspace_tracking
    ON repositories(workspace_id, tracking_enabled, archived);
CREATE INDEX idx_repositories_integration
    ON repositories(github_integration_id);

-- Exactly one row per repository links it to either an active Lead membership
-- or a pending invitation. The CHECK below requires one, but never both.
CREATE TABLE repository_lead_assignments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    -- Removing the current Lead/invitation removes the assignment row. SET NULL
    -- would violate ck_assignment_target because an assignment needs a target.
    lead_membership_id  UUID REFERENCES memberships(id) ON DELETE CASCADE,
    invitation_id       UUID REFERENCES workspace_invitations(id) ON DELETE CASCADE,
    -- Preserve the assignment if the Manager who created it later leaves.
    assigned_by_membership_id UUID REFERENCES memberships(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_assignment_target CHECK (
        (lead_membership_id IS NOT NULL AND invitation_id IS NULL)
        OR
        (lead_membership_id IS NULL AND invitation_id IS NOT NULL)
    ),
    UNIQUE (repository_id)
);

CREATE INDEX idx_lead_assignments_membership
    ON repository_lead_assignments(workspace_id, lead_membership_id)
    WHERE lead_membership_id IS NOT NULL;