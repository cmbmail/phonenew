-- V79: 分摊机构对照表——空成本中心改存 NULL（修复多条空成本中心记录触发唯一索引冲突导致导入 500）
-- 根因：cost_center_code 唯一索引 + NOT NULL DEFAULT ''，表内只允许一条空值；
--       导入多行成本中心为空的数据时第二条起触发 uk_alloc_org_mapping_cost_center 冲突。
-- 方案：列改为 NULL DEFAULT NULL，存量 '' 统一转 NULL（MySQL 唯一索引不约束 NULL，多条记录可同时无成本中心）。

ALTER TABLE allocation_org_mapping
    MODIFY COLUMN cost_center_code VARCHAR(64) NULL DEFAULT NULL COMMENT '成本中心代码（唯一；空存 NULL）';

UPDATE allocation_org_mapping SET cost_center_code = NULL WHERE cost_center_code = '';

-- 版本号更新至 1.12.147
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.147', '分摊机构对照表空成本中心改存 NULL，修复多行空成本中心导入 500', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.147' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.147' AND is_current = 0 AND deleted_at IS NULL;
