-- Normalized GitHub issues used by the project issue dashboard.
-- Pull requests are deliberately excluded by the engine normalizer even though
-- GitHub's REST issues endpoint can return both resource types.
CREATE TABLE github_issues (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    github_issue_id     BIGINT NOT NULL,
    github_node_id      VARCHAR(255),
    number              INTEGER NOT NULL CHECK (number > 0),
    title               TEXT NOT NULL,
    state               VARCHAR(16) NOT NULL CHECK (state IN ('OPEN', 'CLOSED')),
    author_login        VARCHAR(255),
    assignee_logins     TEXT[] NOT NULL DEFAULT '{}',
    labels              TEXT[] NOT NULL DEFAULT '{}',
    comments_count      INTEGER NOT NULL DEFAULT 0 CHECK (comments_count >= 0),
    github_created_at   TIMESTAMPTZ NOT NULL,
    github_updated_at   TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    last_synced_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_data            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (repository_id, github_issue_id),
    UNIQUE (repository_id, number)
);

CREATE INDEX idx_github_issues_repo_open_updated
    ON github_issues(repository_id, github_updated_at DESC, github_created_at DESC)
    WHERE state = 'OPEN';

CREATE INDEX idx_jira_issues_project_open_updated
    ON jira_issues(jira_project_id, jira_updated_at DESC, jira_created_at DESC)
    WHERE resolved_at IS NULL;
