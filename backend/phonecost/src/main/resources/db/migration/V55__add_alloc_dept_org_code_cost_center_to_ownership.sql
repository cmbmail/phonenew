-- V55: Add alloc_dept, org_code, cost_center columns to phone_ownership_entry
-- For phone ownership auto-generation from directory (4-step matching)
-- Idempotent: uses IF + PREPARE/EXECUTE pattern (Flyway does not support DELIMITER)

-- Add alloc_dept column
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS 
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'phone_ownership_entry' AND COLUMN_NAME = 'alloc_dept');
SET @sql = IF(@col_exists = 0, 
    'ALTER TABLE phone_ownership_entry ADD COLUMN alloc_dept VARCHAR(255) DEFAULT '''' AFTER l2_branch', 
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add org_code column
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS 
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'phone_ownership_entry' AND COLUMN_NAME = 'org_code');
SET @sql = IF(@col_exists = 0, 
    'ALTER TABLE phone_ownership_entry ADD COLUMN org_code VARCHAR(50) DEFAULT '''' AFTER alloc_dept', 
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add cost_center column
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS 
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'phone_ownership_entry' AND COLUMN_NAME = 'cost_center');
SET @sql = IF(@col_exists = 0, 
    'ALTER TABLE phone_ownership_entry ADD COLUMN cost_center VARCHAR(50) DEFAULT '''' AFTER org_code', 
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Version tracking via system_version table (system_config does not exist)
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.19', 'V55: add alloc_dept, org_code, cost_center to phone_ownership_entry', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.19');
