-- v1.12.89: 给 allocation_org_entry 新增差异数据列，支持差异推送数据 Tab 展示与数据对比差异数据 Tab 相同的列
ALTER TABLE allocation_org_entry ADD COLUMN username VARCHAR(255) NULL DEFAULT NULL AFTER phone_number;
ALTER TABLE allocation_org_entry ADD COLUMN dept_path TEXT NULL DEFAULT NULL AFTER l1_branch;
ALTER TABLE allocation_org_entry ADD COLUMN extension VARCHAR(50) NULL DEFAULT NULL AFTER dept_path;
ALTER TABLE allocation_org_entry ADD COLUMN change_type VARCHAR(20) NULL DEFAULT NULL AFTER extension;
ALTER TABLE allocation_org_entry ADD COLUMN changed_columns TEXT NULL DEFAULT NULL AFTER change_type;
