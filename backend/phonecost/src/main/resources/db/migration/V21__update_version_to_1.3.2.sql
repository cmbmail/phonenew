-- V21: 更新系统版本至 1.3.2
-- 修复Web UI升级时原子文件替换（防止前端文件丢失）
-- 幂等：已存在则跳过

UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;

INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.3.2', '修复Web UI升级时前端文件原子替换', 1, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.3.2' AND deleted_at IS NULL);

UPDATE system_version SET is_current = 1 WHERE version = '1.3.2' AND is_current = 0 AND deleted_at IS NULL;
