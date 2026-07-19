-- RepositoryLeadAssignment extends BaseEntity, whose createdAt field maps to
-- created_at. Add that audit column without changing the already-shared V2.
ALTER TABLE repository_lead_assignments
    ADD COLUMN created_at TIMESTAMPTZ;

-- Existing assignments were created when their current assignment was made,
-- so assigned_at is the most meaningful historical value for the backfill.
UPDATE repository_lead_assignments
SET created_at = assigned_at;

-- New rows receive a database fallback, while JPA auditing normally supplies
-- the value. Apply NOT NULL only after every existing row has been backfilled.
ALTER TABLE repository_lead_assignments
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN created_at SET NOT NULL;
