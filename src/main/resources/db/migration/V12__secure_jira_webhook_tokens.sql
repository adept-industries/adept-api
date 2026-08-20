-- Jira dynamic webhook callback credentials are generated per integration.
-- Only a peppered HMAC is retained so a database read cannot reveal a usable URL token.
ALTER TABLE jira_integrations
    ADD COLUMN webhook_token_hash VARCHAR(64);

ALTER TABLE jira_integrations
    ADD CONSTRAINT ck_jira_integrations_webhook_token_hash
    CHECK (
        webhook_token_hash IS NULL
        OR webhook_token_hash ~ '^[0-9a-f]{64}$'
    );

CREATE UNIQUE INDEX uq_jira_integrations_webhook_token_hash
    ON jira_integrations(webhook_token_hash)
    WHERE webhook_token_hash IS NOT NULL;

-- Pre-V12 callbacks used the public integration UUID as their only credential.
-- They cannot be accepted securely, so surface them as reconnect-required rather
-- than presenting a false ACTIVE state in the management UI.
UPDATE jira_integrations
SET status = 'ERROR',
    updated_at = now()
WHERE status = 'ACTIVE'
  AND webhook_token_hash IS NULL;

-- Keep at most one retryable future renewal per Jira integration. A RUNNING job
-- is excluded from the unique index so a successful worker can schedule the next
-- renewal before the dispatcher marks the current job SUCCEEDED. Application
-- scheduling still locks RUNNING jobs and leaves them under worker ownership.
WITH duplicate_renewals AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY payload ->> 'jiraIntegrationId'
               ORDER BY created_at ASC, id ASC
           ) AS position
    FROM processing_jobs
    WHERE job_type = 'RENEW_JIRA_WEBHOOK'
      AND status IN ('PENDING', 'FAILED')
      AND payload ->> 'jiraIntegrationId' IS NOT NULL
)
UPDATE processing_jobs
SET status = 'DEAD',
    finished_at = now(),
    last_error = 'Superseded by secure Jira webhook renewal migration',
    locked_at = NULL,
    locked_by = NULL,
    updated_at = now()
WHERE id IN (
    SELECT id FROM duplicate_renewals WHERE position > 1
);

CREATE UNIQUE INDEX uq_processing_jobs_jira_webhook_renewal
    ON processing_jobs ((payload ->> 'jiraIntegrationId'))
    WHERE job_type = 'RENEW_JIRA_WEBHOOK'
      AND status IN ('PENDING', 'FAILED')
      AND payload ->> 'jiraIntegrationId' IS NOT NULL;

-- The normalized deployment model currently represents only GitHub workflow
-- runs and GitHub deployments. Older clients offered signals such as PUSH,
-- RELEASE_TAG, and MERGE_TO_BRANCH that cannot produce a Deployment row. Keep
-- those repositories observable by moving unsupported values to the default
-- workflow-run contract before API validation becomes strict.
UPDATE repositories
SET settings = jsonb_set(settings, '{deploymentSignal}', '"WORKFLOW_RUN"'::jsonb, true),
    updated_at = now()
WHERE settings ? 'deploymentSignal'
  AND COALESCE(settings ->> 'deploymentSignal', '') NOT IN ('WORKFLOW_RUN', 'DEPLOYMENT');
