-- V74: L2/L3 导出改用 raw_data 聚合，修正录音/彩铃/闪信费用精度丢失
-- 问题：BranchBillExportService 的 L2/L3 导出中，录音/彩铃/闪信费用从
--       AllocationResult 列值和 BillDetail 列值读取，V72 前历史数据为 2 位截断。
-- 修复：L2/L3 汇总表录音/彩铃/闪信改从 raw_data JSON 聚合；
--       录音/彩铃/闪信明细 sheet 改从 raw_data 读取原始精度值；
--       L2/L3 数字样式改为原始精度格式。
-- 注意：此迁移仅更新版本号，不涉及表结构变更。
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.141', 'L2/L3 导出改用 raw_data 聚合，修正录音/彩铃/闪信费用精度丢失', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.141' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.141' AND is_current = 0 AND deleted_at IS NULL;
