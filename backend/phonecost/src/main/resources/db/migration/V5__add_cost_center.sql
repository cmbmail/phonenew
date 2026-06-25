-- ============================================================
-- V5: Add cost_center column, make code nullable
-- ============================================================

ALTER TABLE sys_organization MODIFY COLUMN code VARCHAR(50) NULL COMMENT '组织代码';
ALTER TABLE sys_organization ADD COLUMN cost_center VARCHAR(50) NULL COMMENT '成本中心' AFTER code;
UPDATE sys_organization SET code = NULL WHERE code = '';
