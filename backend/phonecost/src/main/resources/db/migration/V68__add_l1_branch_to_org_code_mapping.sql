-- 组织机构对照表新增一级分行列
ALTER TABLE org_code_mapping ADD COLUMN l1_branch VARCHAR(256) NOT NULL DEFAULT '' AFTER id;
