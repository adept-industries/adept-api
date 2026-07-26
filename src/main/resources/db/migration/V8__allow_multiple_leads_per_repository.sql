-- A repository may have multiple Lead assignments, but the same active
-- membership or pending invitation may be assigned to that repository only once.
--
-- Add the narrower pair constraints before dropping the original repository-only
-- constraint so duplicate protection is never absent during this migration.
ALTER TABLE repository_lead_assignments
    ADD CONSTRAINT uq_repository_lead_assignment_membership
        UNIQUE (repository_id, lead_membership_id),
    ADD CONSTRAINT uq_repository_lead_assignment_invitation
        UNIQUE (repository_id, invitation_id);

ALTER TABLE repository_lead_assignments
    DROP CONSTRAINT repository_lead_assignments_repository_id_key;
