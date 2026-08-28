-- V29: Add billing_month to phone_ownership_batch for month-based filtering
ALTER TABLE phone_ownership_batch ADD COLUMN billing_month VARCHAR(7) DEFAULT NULL AFTER exception_count;

-- Update system version
INSERT INTO system_version (version, description, created_at, updated_at)
SELECT '1.9.0', 'Add ownership batch billing month', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.9.0');
