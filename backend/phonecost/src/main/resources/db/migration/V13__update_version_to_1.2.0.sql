-- V13: 更新系统版本至 1.2.0
-- 标记旧版本为非当前，插入新的当前版本记录（幂等：已存在则跳过）
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;

INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.2.0', 'v1.2.0: 安全增强+组织搜索+i18n完善+代码重构', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.2.0' AND deleted_at IS NULL);

-- 如果 1.2.0 已存在但 is_current 不为 1，则修正
UPDATE system_version SET is_current = 1 WHERE version = '1.2.0' AND is_current = 0 AND deleted_at IS NULL;
