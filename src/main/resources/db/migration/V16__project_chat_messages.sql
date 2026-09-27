CREATE TABLE project_chat_messages (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id            UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_id              UUID NOT NULL,
    sender_membership_id    UUID NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    content_enc             TEXT NOT NULL,
    encryption_key_version  INT NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_project_chat_messages_project_workspace
        FOREIGN KEY (project_id, workspace_id)
        REFERENCES projects(id, workspace_id) ON DELETE CASCADE
);

CREATE INDEX idx_project_chat_messages_project_created
    ON project_chat_messages(workspace_id, project_id, created_at ASC);
