-- V80: 分摊机构对照表——取消机构名称/机构代码/成本中心唯一约束，仅部门全路径唯一
-- 业务规则修正：机构名称、机构代码、成本中心代码均可重复（如不同分行均有「营业部」）；
-- 唯一性仅体现在部门全路径（同一一级分行下唯一）。

ALTER TABLE allocation_org_mapping
    DROP INDEX uk_alloc_org_mapping_org_name,
    DROP INDEX uk_alloc_org_mapping_org_code,
    DROP INDEX uk_alloc_org_mapping_cost_center;

-- 版本号更新至 1.12.148
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.148', '分摊机构对照表取消机构名称/代码/成本中心唯一约束，仅部门全路径唯一', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.148' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.148' AND is_current = 0 AND deleted_at IS NULL;
