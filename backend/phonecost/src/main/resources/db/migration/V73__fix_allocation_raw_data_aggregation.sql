-- V73: 分摊计算改用 raw_data 聚合，修正历史数据精度丢失
-- 问题：AllocationService/AllocationAdjustService 直接读取 bill_detail 列值聚合，
--       V72 前导入的历史数据列值为 2 位截断，导致分摊结果精度丢失。
-- 修复：分摊计算改从 raw_data JSON 读取原始精度值（与 L1 汇总一致）。
-- 注意：此迁移仅更新版本号，不涉及表结构变更；需对历史批次重新分摊才能生效。
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.140', '分摊计算改用 raw_data 聚合，修正历史数据精度丢失', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.140' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.140' AND is_current = 0 AND deleted_at IS NULL;
