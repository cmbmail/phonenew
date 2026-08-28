-- V57: Fix database schema issues (H-DB02/04/05/09)
-- Idempotent: uses IF + PREPARE/EXECUTE pattern for all DDL changes
-- H-DB10: bill_batch.total_amount keeps DECIMAL(14,2) — data exceeds DECIMAL(12,2) max

-- ==============================
-- H-DB02: Fix collation on V9/V10 tables
-- ==============================
ALTER TABLE backup_record CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE system_version CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE version_upgrade_package CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ==============================
-- H-DB04: Fix recording_data_batch unique constraint to include deleted_at
-- ==============================
-- Drop old single-column unique constraint on batch_no
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recording_data_batch' AND INDEX_NAME = 'batch_no');
SET @sql = IF(@idx_exists > 0,
    'ALTER TABLE recording_data_batch DROP INDEX batch_no',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add new composite unique constraint including deleted_at
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recording_data_batch' AND INDEX_NAME = 'uk_rdb_batch_no');
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE recording_data_batch ADD UNIQUE INDEX uk_rdb_batch_no (batch_no, deleted_at)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ==============================
-- H-DB05: Fix allocation_dept_batch unique constraint to include deleted_at
-- ==============================
-- Drop old unique constraint on batch_no
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'allocation_dept_batch' AND INDEX_NAME = 'uk_batch_no');
SET @sql = IF(@idx_exists > 0,
    'ALTER TABLE allocation_dept_batch DROP INDEX uk_batch_no',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add new composite unique constraint including deleted_at
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'allocation_dept_batch' AND INDEX_NAME = 'uk_adb_batch_no');
SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE allocation_dept_batch ADD UNIQUE INDEX uk_adb_batch_no (batch_no, deleted_at)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ==============================
-- H-DB09: Change recording_data_entry.status from INT to TINYINT
-- ==============================
SET @col_type = (SELECT DATA_TYPE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recording_data_entry' AND COLUMN_NAME = 'status');
SET @sql = IF(@col_type = 'int',
    'ALTER TABLE recording_data_entry MODIFY COLUMN status TINYINT DEFAULT 0 COMMENT ''当前状态 0=在用 1=停用 2=关闭''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

