-- V82: 分摊机构对照表 -- 取消「机构代码同分行唯一」约束 (v1.12.150)
-- 规则修订：业务确认同一一级分行下，不同机构名称可共用同一机构代码
-- (如佛山分行 狮山支行/罗村支行 共用 106105，中山分行零售金融部/私人银行(中山)中心 共用 107921 等)
-- 保留：
--   规则 1) 同一一级分行下部门全路径唯一 (应用层校验)
--   规则 2) 同一一级分行下机构名称对应的机构代码/成本中心一致 (应用层校验)
--   规则 3) 成本中心不可跨分行 (应用层校验 + 索引 uk_alloc_mapping_branch_name_cost 不受影响)
-- 本迁移仅删除 uk_alloc_mapping_branch_orgcode 唯一索引；应用层同步移除该校验。

-- 兼容处理：若 V81 因存量数据冲突未成功建索引 (success=0 记录被手工删除后重跑的场景)，
-- Flyway 会在 V82 之前重跑 V81，此时索引可能尚不存在，故删除前判断存在性。
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'allocation_org_mapping'
      AND INDEX_NAME = 'uk_alloc_mapping_branch_orgcode'
);
SET @ddl = IF(@idx_exists > 0,
    'ALTER TABLE allocation_org_mapping DROP INDEX uk_alloc_mapping_branch_orgcode',
    'SELECT ''index uk_alloc_mapping_branch_orgcode not exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 版本号更新至 1.12.150
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.150', '分摊机构对照表取消机构代码同分行唯一约束，允许不同机构共用机构代码', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.150' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.150' AND is_current = 0 AND deleted_at IS NULL;
