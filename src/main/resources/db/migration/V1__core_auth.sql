
-- One row is one Adept login account. It is not tied to only one workspace.
CREATE TABLE users (
    -- gen_random_uuid() generates the internal identifier inside PostgreSQL.
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Email uniqueness is enforced case-insensitively by the index below.
    email               VARCHAR(320) NOT NULL,
    -- Store a slow password hash, never the original password.
    password_hash       VARCHAR(255) NOT NULL,
    display_name        VARCHAR(160) NOT NULL,
    avatar_url          TEXT,
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'DISABLED')),
    email_verified_at   TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    token_version       INTEGER NOT NULL DEFAULT 0 CHECK (token_version >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0
);

-- lower(email) makes Alice@example.com and alice@example.com the same login.
CREATE UNIQUE INDEX uq_users_email_lower ON users ((lower(email)));

-- One row is one isolated customer/team tenant.
CREATE TABLE workspaces (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(160) NOT NULL,
    slug                VARCHAR(80) NOT NULL,
    timezone            VARCHAR(64) NOT NULL DEFAULT 'UTC',
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'DELETING', 'DELETED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (slug)
);

-- Joins a user to a workspace and gives that user a role in that workspace.
CREATE TABLE memberships (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role                VARCHAR(16) NOT NULL CHECK (role IN ('MANAGER', 'LEAD')),
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (workspace_id, user_id)
);

-- Speeds “which workspaces can this user enter?” and role/scope checks.
CREATE INDEX idx_memberships_user ON memberships(user_id, status);
CREATE INDEX idx_memberships_workspace_role ON memberships(workspace_id, role, status);

-- Represents an emailed invitation before or after the recipient accepts it.
CREATE TABLE workspace_invitations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    email               VARCHAR(320) NOT NULL,
    role                VARCHAR(16) NOT NULL CHECK (role = 'LEAD'),
    token_hash          VARCHAR(128) NOT NULL UNIQUE,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    -- Preserve the invitation if the inviter leaves; provenance then becomes null.
    invited_by_membership_id UUID REFERENCES memberships(id) ON DELETE SET NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    accepted_at         TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0
);

-- Allows only one still-pending invitation for an email in a workspace.
CREATE UNIQUE INDEX uq_pending_workspace_invitation_email
    ON workspace_invitations(workspace_id, lower(email))
    WHERE status = 'PENDING';

-- Stores hashes of one-use email-verification and password-reset tokens.
CREATE TABLE user_action_tokens (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose             VARCHAR(32) NOT NULL
                        CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    token_hash          VARCHAR(128) NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_action_tokens_user_purpose
    ON user_action_tokens(user_id, purpose, expires_at);

-- Records rotating browser refresh tokens, their family, and reuse detection.
CREATE TABLE refresh_tokens (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id           UUID NOT NULL,
    parent_token_id     UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    token_hash          VARCHAR(128) NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ NOT NULL,
    rotated_at          TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    reuse_detected_at   TIMESTAMPTZ,
    user_agent_hash     VARCHAR(128),
    ip_hash             VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_active
    ON refresh_tokens(user_id, expires_at)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);

-- Links an Adept user to a provider identity such as a GitHub account.
CREATE TABLE external_identities (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider            VARCHAR(16) NOT NULL CHECK (provider IN ('GITHUB', 'JIRA')),
    provider_user_id    VARCHAR(255) NOT NULL,
    login               VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    UNIQUE (provider, provider_user_id),
    UNIQUE (user_id, provider)
);

-- Short-lived OAuth handshake state prevents callback forgery and replay.
CREATE TABLE integration_oauth_states (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider            VARCHAR(16) NOT NULL CHECK (provider IN ('GITHUB', 'JIRA')),
    workspace_id        UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    initiated_by_membership_id UUID NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    state_hash          VARCHAR(128) NOT NULL UNIQUE,
    code_verifier_enc   TEXT,
    redirect_path       VARCHAR(500) NOT NULL DEFAULT '/dashboard/integrations',
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_oauth_states_expiry ON integration_oauth_states(expires_at);