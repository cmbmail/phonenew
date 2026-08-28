-- V32: Add extension and full_path columns to phone_ownership_entry
-- Idempotent: uses IF NOT EXISTS equivalent via information_schema check

SET @dbname = DATABASE();
SET @tablename = 'phone_ownership_entry';

-- Add extension column
SET @colname = 'extension';
SET @preparedStatement = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @colname) = 0,
    'ALTER TABLE phone_ownership_entry ADD COLUMN extension VARCHAR(50) DEFAULT '''' AFTER phone_number',
    'SELECT 1'
));
PREPARE stmt FROM @preparedStatement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add full_path column
SET @colname2 = 'full_path';
SET @preparedStatement2 = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @colname2) = 0,
    'ALTER TABLE phone_ownership_entry ADD COLUMN full_path VARCHAR(500) DEFAULT '''' AFTER extension',
    'SELECT 1'
));
PREPARE stmt2 FROM @preparedStatement2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- Add index on full_path for matching performance
SET @idxname = 'idx_full_path';
SET @preparedStatement3 = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND INDEX_NAME = @idxname) = 0,
    'CREATE INDEX idx_full_path ON phone_ownership_entry(full_path(100))',
    'SELECT 1'
));
PREPARE stmt3 FROM @preparedStatement3;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;
