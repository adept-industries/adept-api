-- One calculated metric value for a repository/workspace and time bucket.
CREATE TABLE metric_snapshots (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    metric_type         VARCHAR(64) NOT NULL
                        CHECK (metric_type IN (
                            'CHANGE_LEAD_TIME_HOURS',
                            'DEPLOYMENT_FREQUENCY',
                            'FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS',
                            'CHANGE_FAILURE_RATE_PERCENT'
                        )),
    granularity         VARCHAR(16) NOT NULL CHECK (granularity IN ('DAY', 'WEEK', 'MONTH')),
    period_start        TIMESTAMPTZ NOT NULL,
    period_end          TIMESTAMPTZ NOT NULL,
    value               NUMERIC(18,6) NOT NULL,
    unit                VARCHAR(32) NOT NULL,
    sample_size         INTEGER NOT NULL DEFAULT 0 CHECK (sample_size >= 0),
    calculation_version VARCHAR(64) NOT NULL,
    dimensions          JSONB NOT NULL DEFAULT '{}'::jsonb,
    calculated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    CHECK (period_end > period_start),
    UNIQUE (repository_id, metric_type, granularity, period_start, period_end, calculation_version)
);

CREATE INDEX idx_metric_snapshots_workspace_type_period
    ON metric_snapshots(workspace_id, metric_type, period_start DESC);
CREATE INDEX idx_metric_snapshots_repo_type_period
    ON metric_snapshots(repository_id, metric_type, period_start DESC);

-- One model-version prediction for one pull request and feature extraction.
CREATE TABLE risk_predictions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    pull_request_id     UUID NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    feature_id          UUID REFERENCES pull_request_features(id) ON DELETE SET NULL,
    model_name          VARCHAR(128) NOT NULL,
    model_version       VARCHAR(64) NOT NULL,
    risk_score          NUMERIC(7,6) NOT NULL CHECK (risk_score >= 0 AND risk_score <= 1),
    risk_level          VARCHAR(16) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    threshold_used      NUMERIC(7,6),
    top_factors         JSONB NOT NULL DEFAULT '[]'::jsonb,
    predicted_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (pull_request_id, model_name, model_version)
);

CREATE INDEX idx_risk_predictions_repo_score
    ON risk_predictions(repository_id, risk_score DESC, predicted_at DESC);

-- User-configured comparison rule and notification destination.
CREATE TABLE alert_rules (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    -- Keep the rule operational if its creator leaves the workspace.
    created_by_membership_id UUID REFERENCES memberships(id) ON DELETE SET NULL,
    name                VARCHAR(160) NOT NULL,
    metric_type         VARCHAR(64) NOT NULL
                        CHECK (metric_type IN (
                            'CHANGE_LEAD_TIME_HOURS',
                            'DEPLOYMENT_FREQUENCY',
                            'FAILED_DEPLOYMENT_RECOVERY_TIME_HOURS',
                            'CHANGE_FAILURE_RATE_PERCENT',
                            'PR_RISK_SCORE'
                        )),
    comparator          VARCHAR(8) NOT NULL CHECK (comparator IN ('GT', 'GTE', 'LT', 'LTE', 'EQ')),
    threshold_value     NUMERIC(18,6) NOT NULL,
    evaluation_window_minutes INTEGER NOT NULL DEFAULT 1440 CHECK (evaluation_window_minutes > 0),
    cooldown_minutes    INTEGER NOT NULL DEFAULT 1440 CHECK (cooldown_minutes >= 0),
    channel             VARCHAR(16) NOT NULL DEFAULT 'EMAIL' CHECK (channel IN ('EMAIL')),
    destination         VARCHAR(320) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT true,
    last_triggered_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_alert_rules_evaluation
    ON alert_rules(enabled, metric_type, repository_id);

-- Durable attempt history for sending one alert event through one channel.
CREATE TABLE notification_deliveries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    repository_id       UUID NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    alert_rule_id       UUID NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
    event_key           VARCHAR(255) NOT NULL,
    channel             VARCHAR(16) NOT NULL CHECK (channel IN ('EMAIL')),
    destination         VARCHAR(320) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'DEAD')),
    payload             JSONB NOT NULL DEFAULT '{}'::jsonb,
    attempts            INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error          TEXT,
    sent_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (alert_rule_id, event_key)
);

CREATE INDEX idx_notification_delivery_status
    ON notification_deliveries(status, created_at);