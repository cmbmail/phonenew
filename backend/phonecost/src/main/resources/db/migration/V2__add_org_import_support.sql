-- ============================================================
-- V2: Add org import support - indexes and schema adjustments
-- ============================================================

-- Make code nullable to allow NULLs for orgs without code
ALTER TABLE sys_organization MODIFY code VARCHAR(50) NULL DEFAULT NULL COMMENT '组织代码(部门代码)';

-- Update existing empty codes to NULL
UPDATE sys_organization SET code = NULL WHERE code = '';

-- Index on sys_organization.is_active for filtering
CREATE INDEX idx_is_active ON sys_organization (is_active);

-- Index on sys_organization.sort_order for tree ordering
CREATE INDEX idx_sort_order ON sys_organization (sort_order);
