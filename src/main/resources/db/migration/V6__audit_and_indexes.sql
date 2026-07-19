-- Append-only record of important actions. Metadata describes safe context;
-- secrets and raw tokens must never be placed in it.
CREATE TABLE audit_logs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    actor_user_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_membership_id UUID REFERENCES memberships(id) ON DELETE SET NULL,
    action              VARCHAR(128) NOT NULL,
    entity_type         VARCHAR(128) NOT NULL,
    entity_id           UUID,
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_hash             VARCHAR(128),
    user_agent          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_workspace_created
    ON audit_logs(workspace_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity
    ON audit_logs(entity_type, entity_id, created_at DESC);

-- Cleanup/maintenance indexes below support their matching scheduled queries.
CREATE INDEX idx_workspace_invitations_expiry
    ON workspace_invitations(status, expires_at);
CREATE INDEX idx_jira_integrations_webhook_renewal
    ON jira_integrations(status, webhook_expires_at);
CREATE INDEX idx_pull_request_issue_links_issue
    ON pull_request_issue_links(jira_issue_id);
CREATE INDEX idx_deployment_pr_links_pr
    ON deployment_pull_requests(pull_request_id);