-- Phase 6 correctness hardening.
--
-- Pull-request commit membership supports commit-based deployment correlation and
-- provides the authoritative earliest commit timestamp used by change lead time.
CREATE TABLE pull_request_commits (
    pull_request_id     UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    commit_sha          VARCHAR(64) NOT NULL,
    committed_at        TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pull_request_id, commit_sha)
);

CREATE INDEX idx_pull_request_commits_sha
    ON pull_request_commits(commit_sha);

ALTER TABLE deployment_pull_requests
    DROP CONSTRAINT deployment_pull_requests_link_method_check;
ALTER TABLE deployment_pull_requests
    ADD CONSTRAINT deployment_pull_requests_link_method_check
    CHECK (link_method IN ('COMMIT_GRAPH', 'MERGE_SHA', 'MERGE_WINDOW', 'MANUAL'));

-- A failed production deployment creates one normalized GitHub incident. The
-- next successful production deployment resolves it idempotently.
WITH duplicate_incidents AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY failed_deployment_id
               ORDER BY (source = 'GITHUB') DESC, updated_at DESC, id DESC
           ) AS duplicate_rank
    FROM incidents
    WHERE failed_deployment_id IS NOT NULL
)
UPDATE incidents
SET failed_deployment_id = NULL,
    updated_at = now(),
    version = version + 1
WHERE id IN (
    SELECT id FROM duplicate_incidents WHERE duplicate_rank > 1
);

CREATE UNIQUE INDEX uq_incidents_failed_deployment
    ON incidents(failed_deployment_id)
    WHERE failed_deployment_id IS NOT NULL;

-- Only one claimable recalculation may exist for a repository. A new event may
-- still queue a follow-up while an earlier recalculation is already RUNNING.
WITH duplicate_jobs AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY repository_id, job_type
               ORDER BY created_at DESC, id DESC
           ) AS duplicate_rank
    FROM processing_jobs
    WHERE job_type = 'RECALCULATE_METRICS'
      AND status IN ('PENDING', 'FAILED')
)
UPDATE processing_jobs
SET status = 'DEAD',
    last_error = 'Superseded by Phase 6 recalculation deduplication migration',
    finished_at = now(),
    updated_at = now(),
    version = version + 1
WHERE id IN (
    SELECT id FROM duplicate_jobs WHERE duplicate_rank > 1
);

CREATE UNIQUE INDEX uq_processing_job_pending_metric_recalculation
    ON processing_jobs(repository_id, job_type)
    WHERE job_type = 'RECALCULATE_METRICS'
      AND status IN ('PENDING', 'FAILED');

-- Normalize existing settings and add the fields used by the production
-- classifier. Existing explicit values always win.
UPDATE repositories
SET settings = settings
    || jsonb_build_object(
        'productionBranchPatterns',
        COALESCE(settings->'productionBranchPatterns', jsonb_build_array(default_branch)),
        'productionEnvironmentPatterns',
        COALESCE(settings->'productionEnvironmentPatterns', '["production", "prod", "live"]'::jsonb),
        'deploymentWorkflowNamePatterns',
        COALESCE(settings->'deploymentWorkflowNamePatterns', '["*deploy*", "*production*", "*release*"]'::jsonb),
        'incidentSource',
        COALESCE(settings->'incidentSource', '"GITHUB"'::jsonb),
        'doraExclusions',
        COALESCE(settings->'doraExclusions', '[]'::jsonb)
    );
