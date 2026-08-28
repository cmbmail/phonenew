-- V60: Version update to 1.12.43 (数据对比归档快照 + 分页优化, BUG-1~8 修复)
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.43', '数据对比归档快照+分页优化 (BUG-1~8 修复)', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.43' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.43' AND is_current = 0 AND deleted_at IS NULL;
