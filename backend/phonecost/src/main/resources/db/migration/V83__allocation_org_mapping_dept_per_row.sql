-- V83: 分摊机构对照表 -- 部门全路径独占一行 (v1.12.152)
-- 规则修订：不再按机构合并部门全路径，每个部门全路径独占一条记录（与 Excel 行结构一致）
-- upsert 键改为 一级分行+部门全路径；同一机构（分行+机构名称）的代码/成本中心仍须一致（应用层校验）

-- 1) 删除组合唯一索引 (一级分行+机构名称+成本中心) -- 该索引假设一机构一条记录，与新规则冲突
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'allocation_org_mapping'
      AND INDEX_NAME = 'uk_alloc_mapping_branch_name_cost'
);
SET @ddl = IF(@idx_exists > 0,
    'ALTER TABLE allocation_org_mapping DROP INDEX uk_alloc_mapping_branch_name_cost',
    'SELECT ''index uk_alloc_mapping_branch_name_cost not exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) dept_full_path 列 TEXT 改 VARCHAR(512)（TEXT 不能建组合唯一索引；一部门一行后路径长度可控）
ALTER TABLE allocation_org_mapping
    MODIFY COLUMN dept_full_path VARCHAR(512) NULL DEFAULT NULL COMMENT '部门全路径（独占一行；同一一级分行下唯一）';

-- 3) 拆分存量多值行：dept_full_path 含「、」的记录拆成多行（每行一个部门全路径）
--    先暂存多值行，删除原行，再按「、」逐段插回（递归 CTE 生成序号 + SUBSTRING_INDEX 取段）
DROP TEMPORARY TABLE IF EXISTS tmp_alloc_split;
CREATE TEMPORARY TABLE tmp_alloc_split AS
SELECT id, l1_branch, org_name, org_code, cost_center_code, remark, created_at, updated_at, dept_full_path
FROM allocation_org_mapping WHERE deleted_at IS NULL AND dept_full_path LIKE '%、%';

DELETE FROM allocation_org_mapping
WHERE deleted_at IS NULL AND dept_full_path LIKE '%、%';

INSERT INTO allocation_org_mapping (l1_branch, org_name, org_code, cost_center_code, dept_full_path, remark, created_at, updated_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100
),
split_src AS (
    SELECT t.*, (LENGTH(t.dept_full_path) - LENGTH(REPLACE(t.dept_full_path, '、', '')) + 1) AS total
    FROM tmp_alloc_split t
)
SELECT s.l1_branch, s.org_name, s.org_code, s.cost_center_code,
       TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(s.dept_full_path, '、', seq.n), '、', -1)) AS dept_full_path,
       s.remark, s.created_at, s.updated_at
FROM split_src s
JOIN seq ON seq.n <= s.total
WHERE TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(s.dept_full_path, '、', seq.n), '、', -1)) <> '';

DROP TEMPORARY TABLE IF EXISTS tmp_alloc_split;

-- 4) 新增唯一索引 (一级分行+部门全路径) -- 规则1数据库层兜底
--    先物理清理软删残留行（唯一索引覆盖软删行，历史软删记录会挡住索引创建）
DELETE FROM allocation_org_mapping WHERE deleted_at IS NOT NULL;
ALTER TABLE allocation_org_mapping
    ADD UNIQUE INDEX uk_alloc_mapping_branch_dept (l1_branch, dept_full_path);

-- 版本号更新至 1.12.152
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.152', '分摊机构对照表部门全路径独占一行，upsert 键改为分行+部门全路径', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.152' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.152' AND is_current = 0 AND deleted_at IS NULL;
