-- V76: 版本号对齐 1.12.144（费用列前端统一两位小数，v1.12.144）
-- 仅更新版本号，表结构无变更

UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.144', '费用列前端统一显示两位小数（仅显示层，后端数据保持原始精度）', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.144' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.144' AND is_current = 0 AND deleted_at IS NULL;

-- 历史版本号格式统一：去除 v 前缀
-- （1.12.43 之前的记录曾带 v 前缀，与升级服务 normalize 规则不一致；实际数据中无同名不带前缀记录，无冲突）
UPDATE system_version SET version = SUBSTRING(version, 2)
WHERE version LIKE 'v1.%' AND deleted_at IS NULL;
