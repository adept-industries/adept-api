-- PR Risk Analytics Schema Migration

-- 1. Feature snapshots table storing leakage-safe point-in-time features.
CREATE TABLE pr_feature_snapshots (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    pull_request_id     UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    pr_number           INTEGER NOT NULL CHECK (pr_number > 0),
    snapshot_at         TIMESTAMPTZ NOT NULL,
    stage               VARCHAR(32) NOT NULL DEFAULT 'live'
                        CHECK (stage IN ('initial', 'live', 'historical')),
    feature_schema_version VARCHAR(64) NOT NULL,
    features            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_pr_feature_snapshot UNIQUE (repository_id, pull_request_id, snapshot_at, stage)
);

CREATE INDEX idx_pr_feature_snapshots_repo_pr
    ON pr_feature_snapshots(repository_id, pull_request_id, snapshot_at DESC);
CREATE INDEX idx_pr_feature_snapshots_workspace
    ON pr_feature_snapshots(workspace_id, snapshot_at DESC);

-- 2. Ensure risk_predictions supports stage and optimized lookup indexes.
ALTER TABLE risk_predictions
    ADD COLUMN IF NOT EXISTS stage VARCHAR(32) NOT NULL DEFAULT 'live'
    CHECK (stage IN ('initial', 'live', 'historical'));

CREATE INDEX IF NOT EXISTS idx_risk_predictions_repo_pr_predicted
    ON risk_predictions(repository_id, pull_request_id, predicted_at DESC);
CREATE INDEX IF NOT EXISTS idx_risk_predictions_risk_level
    ON risk_predictions(risk_level);

-- 3. PR outcome and strong adverse event labeling table.
CREATE TABLE pr_outcomes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    pull_request_id     UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    pr_number           INTEGER NOT NULL CHECK (pr_number > 0),
    merged_at           TIMESTAMPTZ,
    observed_until      TIMESTAMPTZ NOT NULL,
    is_risky            BOOLEAN NOT NULL DEFAULT false,
    reason              VARCHAR(64) NOT NULL,
    evidence            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_pr_outcome UNIQUE (repository_id, pull_request_id)
);

CREATE INDEX idx_pr_outcomes_repo_risky
    ON pr_outcomes(repository_id, is_risky);
CREATE INDEX idx_pr_outcomes_workspace
    ON pr_outcomes(workspace_id, observed_until DESC);

-- 4. Model Registry for tracking trained models, versions, metrics, and thresholds.
CREATE TABLE model_registry (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_name          VARCHAR(128) NOT NULL,
    model_version       VARCHAR(128) NOT NULL UNIQUE,
    trained_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    feature_schema_version VARCHAR(64) NOT NULL,
    feature_names       JSONB NOT NULL DEFAULT '[]'::jsonb,
    thresholds          JSONB NOT NULL DEFAULT '{}'::jsonb,
    train_range         JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics             JSONB NOT NULL DEFAULT '{}'::jsonb,
    artifact_path       TEXT NOT NULL,
    artifact_hash       VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_model_registry_trained_at
    ON model_registry(trained_at DESC);

-- 5. Expand processing_jobs job_type check to include EVALUATE_PR_RISK.
ALTER TABLE processing_jobs DROP CONSTRAINT IF EXISTS processing_jobs_job_type_check;
ALTER TABLE processing_jobs ADD CONSTRAINT processing_jobs_job_type_check
    CHECK (job_type IN (
        'PROCESS_GITHUB_EVENT', 'PROCESS_JIRA_EVENT',
        'SYNC_GITHUB_REPOSITORIES', 'BACKFILL_REPOSITORY',
        'SYNC_JIRA_PROJECTS', 'RENEW_JIRA_WEBHOOK',
        'RECALCULATE_METRICS', 'EVALUATE_ALERTS',
        'DELETE_WORKSPACE', 'EVALUATE_PR_RISK'
    ));
