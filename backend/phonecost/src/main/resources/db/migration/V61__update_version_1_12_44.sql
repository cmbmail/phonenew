-- V61: Version update to 1.12.44 (数据对比页面前端框架重构 + 通讯录明细 search 后端修复)
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.44', '数据对比页面框架重构(月份+批次) + 通讯录明细search后端修复', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.44' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.44' AND is_current = 0 AND deleted_at IS NULL;
