-- V25: Add department ownership fields to directory_entry
-- Idempotent: safe to re-run if columns already exist

-- Update version to 1.5.0
INSERT INTO system_version (id, version, updated_at)
SELECT 1, '1.5.0', NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE id = 1 AND version >= '1.5.0');

UPDATE system_version SET version = '1.5.0', updated_at = NOW()
WHERE id = 1 AND version < '1.5.0';
