-- ============================================================
-- V2: Add org import support - indexes and schema adjustments
-- Idempotent: uses IF NOT EXISTS / procedural checks
-- ============================================================

-- Make code nullable to allow NULLs for orgs without code
ALTER TABLE sys_organization MODIFY code VARCHAR(50) NULL DEFAULT NULL COMMENT '组织代码(部门代码)';

-- Update existing empty codes to NULL
UPDATE sys_organization SET code = NULL WHERE code = '';

-- Index on sys_organization.is_active for filtering (idempotent)
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'sys_organization' AND index_name = 'idx_is_active');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_is_active ON sys_organization (is_active)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Index on sys_organization.sort_order for tree ordering (idempotent)
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'sys_organization' AND index_name = 'idx_sort_order');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_sort_order ON sys_organization (sort_order)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
