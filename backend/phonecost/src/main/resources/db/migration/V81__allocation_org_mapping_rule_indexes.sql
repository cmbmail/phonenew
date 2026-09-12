-- V81: 分摊机构对照表——按业务规则建立组合唯一索引（v1.12.149）
-- 规则：
--   1) 同一一级分行下，部门全路径唯一；同一部门全路径可出现在不同一级分行
--   2) 同一一级分行下，机构名称对应的机构代码和成本中心唯一（组合：一级分行+机构代码、一级分行+机构名称+成本中心）
--   3) 同一成本中心不能出现在不同分行（成本中心全局唯一）
-- 说明：应用层在导入/新增/编辑时做同批次校验并逐行提示；数据库层加组合唯一索引兜底。

-- 清理软删除残留行（唯一索引覆盖软删行，历史测试/删除记录会挡住索引创建；
-- 该表为可重导的对照数据，软删记录无保留价值）
DELETE FROM allocation_org_mapping WHERE deleted_at IS NOT NULL;

-- 1) 一级分行 + 机构代码 唯一（同分行下机构代码不可重复）
ALTER TABLE allocation_org_mapping
    ADD UNIQUE INDEX uk_alloc_mapping_branch_orgcode (l1_branch, org_code);

-- 2) 一级分行 + 机构名称 + 成本中心 唯一（同分行下同名机构的成本中心一致；
--    空成本中心允许多行，NULL 不受唯一索引约束）
ALTER TABLE allocation_org_mapping
    ADD UNIQUE INDEX uk_alloc_mapping_branch_name_cost (l1_branch, org_name, cost_center_code);

-- 版本号更新至 1.12.149
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.149', '分摊机构对照表业务规则校验：部门全路径同分行唯一、机构代码同分行唯一、成本中心全局唯一', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.149' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.149' AND is_current = 0 AND deleted_at IS NULL;
