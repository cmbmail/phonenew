-- V54: Add allocation_dept_batch_id to data_snapshot table (v1.12.13 - monthly aggregation)
-- Idempotent: only add column if not exists
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'data_snapshot' AND COLUMN_NAME = 'allocation_dept_batch_id');
SET @sql = IF(@col_exists = 0, 
    'ALTER TABLE data_snapshot ADD COLUMN allocation_dept_batch_id BIGINT DEFAULT NULL AFTER directory_batch_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
