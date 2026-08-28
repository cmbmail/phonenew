-- V30: Update version to 1.10.0 (fee analysis fallback + performance optimization)
-- Idempotent: only insert if not exists
INSERT INTO system_version (version, description, created_at, updated_at)
SELECT '1.10.0', 'Fee analysis bill_detail fallback + SQL aggregation optimization', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.10.0');
