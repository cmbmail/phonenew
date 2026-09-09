-- V78: 版本号对齐 1.12.146（号码分摊机构页面：分机号/部门全路径实时匹配展示，v1.12.146）
-- 仅更新版本号，表结构无变更

UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.146', '号码分摊机构：分机号/部门全路径实时匹配展示，分摊部门/机构代码/成本中心取自分摊机构对照表', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.146' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.146' AND is_current = 0 AND deleted_at IS NULL;