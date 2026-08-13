-- Google is an authentication provider, not a workspace integration. Keep its
-- immutable subject mapping in a dedicated table and never persist provider
-- access, refresh, or ID tokens.
ALTER TABLE users
    ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE google_auth_accounts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    google_subject          VARCHAR(255) NOT NULL,
    google_email            VARCHAR(320) NOT NULL,
    last_authenticated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_google_auth_accounts_subject_not_blank
        CHECK (btrim(google_subject) <> ''),
    CONSTRAINT uq_google_auth_accounts_user UNIQUE (user_id),
    CONSTRAINT uq_google_auth_accounts_subject UNIQUE (google_subject)
);

