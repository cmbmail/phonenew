-- v1.12.156 号码分摊机构页 Tab 重构：
-- 1) 删除「待核对号码」Tab（push 来源数据）：
--    - PUSH-COMP-（数据对比-通讯录差异推送）历史批次改前缀为 COMP-，随导入批次展示在「号码分摊机构」Tab
--    - BRN-（分行号码推送）保持归入「号码分摊机构」Tab（前缀不变，查询条件已调整）
-- 2) 新增「例外号码清单」Tab：
--    - PUSH-EXC-（数据对比-例外数据推送）保持前缀不变，作为例外号码清单数据源
--    - 例外号码清单查询时自动与上一自然月通讯录按号码匹配，对比用户名称/分机号/部门全路径
--    - 有差异的号码移入「差异数据」Tab 展示（清单中不显示）
-- 历史数据迁移：仅调整 PUSH-COMP- 批次号前缀，不改变数据内容（含已删除批次，保证前缀语义一致）
UPDATE allocation_org_batch
SET batch_no = CONCAT('COMP-', SUBSTRING(batch_no, 11)),
    updated_at = NOW()
WHERE batch_no LIKE 'PUSH-COMP-%';
