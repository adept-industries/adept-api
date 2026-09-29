-- Code-review cycle time.
--
-- Each submitted GitHub review is kept so the first human review survives later
-- reviews and dismissals. The engine replaces a pull request's rows from GitHub's
-- review list on every sync, so rows are immutable snapshots of provider state
-- rather than versioned entities.
CREATE TABLE pull_request_reviews (
    pull_request_id     UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    github_review_id    BIGINT NOT NULL,
    reviewer_login      VARCHAR(255),
    reviewer_is_bot     BOOLEAN NOT NULL DEFAULT false,
    state               VARCHAR(32) NOT NULL
                        CHECK (state IN ('APPROVED', 'CHANGES_REQUESTED', 'COMMENTED', 'DISMISSED')),
    submitted_at        TIMESTAMPTZ NOT NULL,
    commit_sha          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (pull_request_id, github_review_id)
);

CREATE INDEX idx_pull_request_reviews_pr_submitted
    ON pull_request_reviews(pull_request_id, submitted_at);

-- Draft pull requests are not waiting for review. The most recent
-- ready_for_review transition starts the pickup stage when it is known.
ALTER TABLE pull_requests
    ADD COLUMN ready_for_review_at TIMESTAMPTZ;

-- Cycle-time stages are stored as pooled per-PR observations, like DORA metrics.
ALTER TABLE metric_snapshots
    DROP CONSTRAINT metric_snapshots_metric_type_check;
ALTER TABLE metric_snapshots
    ADD CONSTRAINT metric_snapshots_metric_type_check
    CHECK (metric_type IN (
        'CHANGE_LEAD_TIME_HOURS',
        'DEPLOYMENT_FREQUENCY',
        'FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS',
        'CHANGE_FAILURE_RATE_PERCENT',
        'PR_CODING_TIME_HOURS',
        'PR_PICKUP_TIME_HOURS',
        'PR_REVIEW_TIME_HOURS',
        'PR_DEPLOY_TIME_HOURS'
    ));

-- Pull requests merged before review ingestion have no stored reviews and would
-- be reported as merged without one. Queue one reviews-only backfill per tracked
-- repository: the engine refreshes reviews of PRs merged in the last 90 days and
-- then recalculates metrics. Low priority keeps live webhook work ahead of it.
INSERT INTO processing_jobs (workspace_id, repository_id, job_type, payload, priority)
SELECT r.workspace_id,
       r.id,
       'BACKFILL_REPOSITORY',
       jsonb_build_object('repositoryId', r.id::text, 'backfillDays', 90, 'reviewsOnly', true),
       200
FROM repositories r
JOIN github_integrations gi ON gi.id = r.github_integration_id
WHERE r.tracking_enabled
  AND NOT r.archived
  AND gi.status = 'ACTIVE';
