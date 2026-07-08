-- V20: 更新系统版本至 1.3.1
-- 修正账单导入模板为4个Sheet（按号码费用/录音费用/彩铃费用/闪信费用）
-- 幂等：已存在则跳过

UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;

INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.3.1', '修正账单导入模板为4个Sheet格式', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.3.1' AND deleted_at IS NULL);

-- 如果 1.3.1 已存在但 is_current 不为 1，则修正
UPDATE system_version SET is_current = 1 WHERE version = '1.3.1' AND is_current = 0 AND deleted_at IS NULL;
