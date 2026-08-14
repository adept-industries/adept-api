-- Preserve the time of the last interactive authentication independently of
-- refresh-token rotation. Existing sessions stay valid, but are intentionally
-- treated as not recently authenticated until the user verifies again.
ALTER TABLE refresh_tokens
    ADD COLUMN authenticated_at TIMESTAMPTZ;

