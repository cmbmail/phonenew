-- V22: 更新系统版本至 1.3.3
-- 新增账单批次删除功能（级联软删除）
-- 修复@Where注解不生效问题，改用显式查询过滤
-- 幂等：已存在则跳过

UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;

INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.3.3', '新增账单删除功能', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.3.3' AND deleted_at IS NULL);

UPDATE system_version SET is_current = 1 WHERE version = '1.3.3' AND is_current = 0 AND deleted_at IS NULL;
