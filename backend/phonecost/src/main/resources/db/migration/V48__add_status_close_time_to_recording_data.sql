-- V48: Add status and close_time columns to recording_data_entry (v1.12.7)

-- Idempotent: check if column 'status' exists before adding
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recording_data_entry' AND COLUMN_NAME = 'status');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE recording_data_entry ADD COLUMN status INT DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Idempotent: check if column 'close_time' exists before adding
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recording_data_entry' AND COLUMN_NAME = 'close_time');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE recording_data_entry ADD COLUMN close_time DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
