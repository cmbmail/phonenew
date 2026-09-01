-- V75: 账单导入改用 READ_CELL_DATA 模式，DECIMAL 列保留 Excel 原始精度（v1.12.143）
-- 仅更新版本号，表结构无变更（V72 已扩展为 DECIMAL(22,15)）

UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.143', '账单导入保留Excel原始精度（READ_CELL_DATA）', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.143' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.143' AND is_current = 0 AND deleted_at IS NULL;
