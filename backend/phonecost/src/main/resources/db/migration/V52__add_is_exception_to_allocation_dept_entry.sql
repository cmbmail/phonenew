-- V52: Add is_exception column to allocation_dept_entry (idempotent)
SET @dbname = DATABASE();
SET @tablename = 'allocation_dept_entry';
SET @columnname = 'is_exception';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @columnname) > 0,
  'SELECT 1',
  'ALTER TABLE allocation_dept_entry ADD COLUMN is_exception TINYINT DEFAULT 0 AFTER cost_center'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
