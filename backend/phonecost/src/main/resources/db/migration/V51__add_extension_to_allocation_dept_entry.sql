-- V51: Add extension column to allocation_dept_entry (idempotent)
SET @dbname = DATABASE();
SET @tablename = 'allocation_dept_entry';
SET @columnname = 'extension';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @columnname) > 0,
  'SELECT 1',
  'ALTER TABLE allocation_dept_entry ADD COLUMN extension VARCHAR(255) DEFAULT '''' AFTER phone_number'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
