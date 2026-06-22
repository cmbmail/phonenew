-- ============================================================
-- V2: Add org import support - indexes and schema adjustments
-- ============================================================

-- Make code nullable so unique index works (NULL != NULL in MySQL)
ALTER TABLE sys_organization MODIFY code VARCHAR(50) NULL DEFAULT NULL COMMENT '组织代码(部门代码)';

-- Update existing empty codes to NULL for unique constraint compatibility
UPDATE sys_organization SET code = NULL WHERE code = '';

-- Unique index on sys_organization.code (cost center code uniqueness, NULLs allowed)
CREATE UNIQUE INDEX uk_org_code ON sys_organization (code);

-- Index on sys_organization.is_active for filtering
CREATE INDEX idx_is_active ON sys_organization (is_active);

-- Index on sys_organization.sort_order for tree ordering
CREATE INDEX idx_sort_order ON sys_organization (sort_order);
