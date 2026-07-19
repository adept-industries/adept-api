-- Immutable-at-receipt copy of a verified or rejected provider webhook.
CREATE TABLE raw_webhook_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID REFERENCES repositories(id) ON DELETE CASCADE,
    source              VARCHAR(16) NOT NULL CHECK (source IN ('GITHUB', 'JIRA')),
    delivery_id         VARCHAR(255) NOT NULL,
    event_type          VARCHAR(128) NOT NULL,
    action              VARCHAR(128),
    headers             JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload             JSONB NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'RECEIVED'
                        CHECK (status IN ('RECEIVED', 'QUEUED', 'PROCESSING', 'PROCESSED', 'FAILED', 'IGNORED')),
    signature_valid     BOOLEAN NOT NULL DEFAULT false,
    attempt_count       INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error          TEXT,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    processing_started_at TIMESTAMPTZ,
    processed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (source, delivery_id)
);

CREATE INDEX idx_raw_events_status_received
    ON raw_webhook_events(status, received_at);
CREATE INDEX idx_raw_events_workspace_repository
    ON raw_webhook_events(workspace_id, repository_id, received_at DESC);

-- Durable work queue. Workers claim these rows instead of relying on memory.
CREATE TABLE processing_jobs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID REFERENCES repositories(id) ON DELETE CASCADE,
    raw_event_id        UUID REFERENCES raw_webhook_events(id) ON DELETE CASCADE,
    job_type            VARCHAR(64) NOT NULL
                        CHECK (job_type IN (
                            'PROCESS_GITHUB_EVENT', 'PROCESS_JIRA_EVENT',
                            'SYNC_GITHUB_REPOSITORIES', 'BACKFILL_REPOSITORY',
                            'SYNC_JIRA_PROJECTS', 'RENEW_JIRA_WEBHOOK',
                            'RECALCULATE_METRICS', 'EVALUATE_ALERTS',
                            'DELETE_WORKSPACE'
                        )),
    payload             JSONB NOT NULL DEFAULT '{}'::jsonb,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD')),
    priority            INTEGER NOT NULL DEFAULT 100,
    attempts            INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts        INTEGER NOT NULL DEFAULT 8 CHECK (max_attempts > 0),
    available_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at           TIMESTAMPTZ,
    locked_by           VARCHAR(128),
    last_error          TEXT,
    finished_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_processing_job_event_type
    ON processing_jobs(raw_event_id, job_type)
    WHERE raw_event_id IS NOT NULL;
CREATE INDEX idx_processing_jobs_claim
    ON processing_jobs(status, available_at, priority, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- Current normalized state and timestamps for one GitHub pull request.
CREATE TABLE pull_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    github_pr_id        BIGINT NOT NULL,
    github_node_id      VARCHAR(255),
    number              INTEGER NOT NULL CHECK (number > 0),
    title               TEXT NOT NULL,
    state               VARCHAR(16) NOT NULL CHECK (state IN ('OPEN', 'CLOSED', 'MERGED')),
    draft               BOOLEAN NOT NULL DEFAULT false,
    author_login        VARCHAR(255),
    base_ref            VARCHAR(255) NOT NULL,
    head_ref            VARCHAR(255) NOT NULL,
    head_sha            VARCHAR(64),
    merge_commit_sha    VARCHAR(64),
    additions           INTEGER NOT NULL DEFAULT 0 CHECK (additions >= 0),
    deletions           INTEGER NOT NULL DEFAULT 0 CHECK (deletions >= 0),
    changed_files       INTEGER NOT NULL DEFAULT 0 CHECK (changed_files >= 0),
    commit_count        INTEGER NOT NULL DEFAULT 0 CHECK (commit_count >= 0),
    opened_at           TIMESTAMPTZ NOT NULL,
    first_commit_at     TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    merged_at           TIMESTAMPTZ,
    last_synced_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_data            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (repository_id, github_pr_id),
    UNIQUE (repository_id, number)
);

CREATE INDEX idx_pull_requests_repo_merged
    ON pull_requests(repository_id, merged_at DESC)
    WHERE state = 'MERGED';

-- Versioned numeric/model inputs extracted from a pull request.
CREATE TABLE pull_request_features (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    pull_request_id     UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    feature_schema_version VARCHAR(64) NOT NULL,
    lines_added         INTEGER NOT NULL DEFAULT 0,
    lines_deleted       INTEGER NOT NULL DEFAULT 0,
    files_changed       INTEGER NOT NULL DEFAULT 0,
    commit_count        INTEGER NOT NULL DEFAULT 0,
    author_prior_pr_count INTEGER NOT NULL DEFAULT 0,
    author_prior_merge_rate DOUBLE PRECISION,
    test_file_ratio     DOUBLE PRECISION,
    entropy             DOUBLE PRECISION,
    feature_payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    extracted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (pull_request_id, feature_schema_version)
);

-- Normalized deployment or workflow run used for DORA calculations.
CREATE TABLE deployments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    source              VARCHAR(32) NOT NULL
                        CHECK (source IN ('GITHUB_DEPLOYMENT', 'GITHUB_WORKFLOW', 'MANUAL')),
    external_deployment_id VARCHAR(255) NOT NULL,
    environment         VARCHAR(255) NOT NULL,
    is_production       BOOLEAN NOT NULL DEFAULT false,
    status              VARCHAR(24) NOT NULL
                        CHECK (status IN ('QUEUED', 'IN_PROGRESS', 'SUCCESS', 'FAILURE', 'CANCELLED')),
    commit_sha          VARCHAR(64) NOT NULL,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    raw_data            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (repository_id, source, external_deployment_id)
);

CREATE INDEX idx_deployments_repo_production_time
    ON deployments(repository_id, is_production, finished_at DESC);

-- Many-to-many link explaining which pull requests a deployment contains.
CREATE TABLE deployment_pull_requests (
    deployment_id       UUID NOT NULL REFERENCES deployments(id) ON DELETE CASCADE,
    pull_request_id     UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    link_method         VARCHAR(32) NOT NULL
                        CHECK (link_method IN ('COMMIT_GRAPH', 'MERGE_SHA', 'MANUAL')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (deployment_id, pull_request_id)
);

-- Normalized subset of a Jira issue plus its original flexible JSON payload.
CREATE TABLE jira_issues (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    jira_project_id     UUID NOT NULL REFERENCES jira_projects(id) ON DELETE CASCADE,
    jira_issue_id       VARCHAR(255) NOT NULL,
    issue_key           VARCHAR(64) NOT NULL,
    issue_type          VARCHAR(128),
    status_name         VARCHAR(128),
    priority_name       VARCHAR(128),
    summary             TEXT NOT NULL,
    is_incident         BOOLEAN NOT NULL DEFAULT false,
    jira_created_at     TIMESTAMPTZ,
    jira_updated_at     TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    raw_data            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (jira_project_id, jira_issue_id),
    UNIQUE (workspace_id, issue_key)
);

-- Connects a PR to an issue and records how confidently the link was found.
CREATE TABLE pull_request_issue_links (
    pull_request_id     UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    jira_issue_id       UUID NOT NULL REFERENCES jira_issues(id) ON DELETE CASCADE,
    link_source         VARCHAR(32) NOT NULL
                        CHECK (link_source IN ('BRANCH', 'COMMIT', 'PR_TITLE', 'PR_BODY', 'MANUAL')),
    confidence          DOUBLE PRECISION NOT NULL DEFAULT 1.0
                        CHECK (confidence >= 0.0 AND confidence <= 1.0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pull_request_id, jira_issue_id)
);

-- Operational failure record used to measure recovery time and failure rate.
CREATE TABLE incidents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID REFERENCES repositories(id) ON DELETE SET NULL,
    jira_issue_id       UUID UNIQUE REFERENCES jira_issues(id) ON DELETE SET NULL,
    source              VARCHAR(16) NOT NULL CHECK (source IN ('JIRA', 'MANUAL', 'GITHUB')),
    title               TEXT NOT NULL,
    severity            VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
                        CHECK (severity IN ('UNKNOWN', 'SEV1', 'SEV2', 'SEV3', 'SEV4')),
    status              VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    failed_deployment_id UUID REFERENCES deployments(id) ON DELETE SET NULL,
    recovery_deployment_id UUID REFERENCES deployments(id) ON DELETE SET NULL,
    detected_at         TIMESTAMPTZ NOT NULL,
    resolved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_incident_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    )
);

CREATE INDEX idx_incidents_repository_detected
    ON incidents(repository_id, detected_at DESC);