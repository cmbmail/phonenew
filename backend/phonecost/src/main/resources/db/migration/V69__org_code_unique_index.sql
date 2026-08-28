-- 组织机构对照表 org_code 唯一索引
-- 清理所有可能导致唯一索引冲突的重复 org_code
-- 策略：对于重复的 org_code，保留 id 最小的那条不变，其余都加后缀 _del_<id>

-- 1. 找出所有有重复的 org_code，标记需要改名的记录（保留最小 id）
DROP TEMPORARY TABLE IF EXISTS tmp_dup_org_codes;
CREATE TEMPORARY TABLE tmp_dup_org_codes AS
SELECT m.id, m.org_code
FROM org_code_mapping m
INNER JOIN (
    SELECT org_code, MIN(id) AS keep_id, COUNT(*) AS cnt
    FROM org_code_mapping
    GROUP BY org_code
    HAVING cnt > 1
) dup ON m.org_code = dup.org_code AND m.id <> dup.keep_id;

-- 2. 改名重复记录
UPDATE org_code_mapping m
INNER JOIN tmp_dup_org_codes t ON m.id = t.id
SET m.org_code = CONCAT(m.org_code, '_del_', m.id);

DROP TEMPORARY TABLE IF EXISTS tmp_dup_org_codes;

-- 3. 创建唯一索引
CREATE UNIQUE INDEX uk_org_code_mapping_org_code ON org_code_mapping (org_code);
