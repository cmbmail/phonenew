-- V15: Add optimistic locking version column to bill_detail (M-38)
-- Idempotent: only add if column doesn't exist
SET @dbname = DATABASE();
SET @tablename = 'bill_detail';
SET @columnname = 'version';
SET @preparedStatement = (SELECT IF(
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @columnname) > 0,
  'SELECT 1',
  'ALTER TABLE bill_detail ADD COLUMN version BIGINT DEFAULT 0'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;
