-- v1.12.84: 给 allocation_org_entry 增加 branch_org_id 字段，支持条目级分行数据隔离
ALTER TABLE allocation_org_entry ADD COLUMN branch_org_id BIGINT NULL DEFAULT NULL AFTER l1_branch;

-- 为分行数据隔离查询创建索引
CREATE INDEX idx_alloc_org_entry_branch ON allocation_org_entry (branch_org_id);

-- 回填已有推送数据：按 l1_branch 匹配 sys_organization(type=2) 的 name 填充 branch_org_id
UPDATE allocation_org_entry e
INNER JOIN sys_organization o ON e.l1_branch COLLATE utf8mb4_unicode_ci = o.name AND o.type = 2 AND o.deleted_at IS NULL
SET e.branch_org_id = o.id
WHERE e.deleted_at IS NULL;
