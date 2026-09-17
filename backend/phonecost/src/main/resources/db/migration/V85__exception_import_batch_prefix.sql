-- v1.12.160：例外清单导入批次与推送批次前缀分离
-- 导入批次（例外号码清单 Tab 上传产生）改为 EXC-IMP- 前缀；推送批次（数据对比页推送产生）保持 PUSH-EXC- 前缀
-- 历史导入批次识别特征：PUSH-EXC- 前缀且 file_name 不以「例外号码清单推送」开头（推送批次 file_name 固定为「例外号码清单推送_月份」）
-- 迁移：将历史导入批次前缀替换为 EXC-IMP-
UPDATE allocation_org_batch
SET batch_no = CONCAT('EXC-IMP-', SUBSTRING(batch_no, 10))
WHERE batch_no LIKE 'PUSH-EXC-%'
  AND deleted_at IS NULL
  AND file_name NOT LIKE '例外号码清单推送%';
