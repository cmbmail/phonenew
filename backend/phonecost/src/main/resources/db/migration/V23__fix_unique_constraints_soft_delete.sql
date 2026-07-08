-- V23: Fix unique constraints to include deleted_at for soft-delete compatibility
-- Problem: soft-deleted rows block re-inserting same business key values
-- Solution: include deleted_at in unique constraint (NULL for active rows, unique timestamp for deleted rows)

-- sys_user: username must be unique only among active users
ALTER TABLE sys_user DROP INDEX uk_username,
  ADD UNIQUE INDEX uk_username (username, deleted_at);

-- phone_ownership_batch: batch_no must be unique only among active batches
ALTER TABLE phone_ownership_batch DROP INDEX uk_batch_no,
  ADD UNIQUE INDEX uk_batch_no (batch_no, deleted_at);

-- directory_batch: batch_no must be unique only among active batches
ALTER TABLE directory_batch DROP INDEX uk_batch_no,
  ADD UNIQUE INDEX uk_batch_no (batch_no, deleted_at);

-- bill_batch: batch_no must be unique only among active batches
ALTER TABLE bill_batch DROP INDEX uk_batch_no,
  ADD UNIQUE INDEX uk_batch_no (batch_no, deleted_at);

-- allocation_result: (batch_id, org_id) must be unique only among active results
ALTER TABLE allocation_result DROP INDEX uk_batch_org,
  ADD UNIQUE INDEX uk_batch_org (batch_id, org_id, deleted_at);

-- system_version: version must be unique only among active versions
ALTER TABLE system_version DROP INDEX uk_version,
  ADD UNIQUE INDEX uk_version (version, deleted_at);

-- Update system version
INSERT INTO system_version (version, description, created_at, updated_at)
SELECT '1.4.0', 'Unique constraints include deleted_at for soft-delete compatibility', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.4.0');
