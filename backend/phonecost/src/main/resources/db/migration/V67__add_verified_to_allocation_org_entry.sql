-- 号码分摊机构 entry 增加核对状态字段
ALTER TABLE allocation_org_entry ADD COLUMN verified TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE allocation_org_entry ADD COLUMN verified_at DATETIME NULL;
ALTER TABLE allocation_org_entry ADD COLUMN verified_by BIGINT NULL;
