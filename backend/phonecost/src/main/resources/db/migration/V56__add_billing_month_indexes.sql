-- V56: Add billing_month indexes to batch tables for query performance
-- These tables are frequently queried by billing_month but lack indexes
-- Idempotent: uses IF + PREPARE/EXECUTE pattern (MySQL 8 does not support CREATE INDEX IF NOT EXISTS)

-- recording_data_batch: billing_month index
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS 
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'recording_data_batch' AND INDEX_NAME = 'idx_rdb_billing_month');
SET @sql = IF(@idx_exists = 0, 
    'CREATE INDEX idx_rdb_billing_month ON recording_data_batch (billing_month)', 
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- allocation_dept_batch: billing_month index
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS 
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'allocation_dept_batch' AND INDEX_NAME = 'idx_adb_billing_month');
SET @sql = IF(@idx_exists = 0, 
    'CREATE INDEX idx_adb_billing_month ON allocation_dept_batch (billing_month)', 
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- phone_ownership_batch: billing_month index
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS 
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'phone_ownership_batch' AND INDEX_NAME = 'idx_pob_billing_month');
SET @sql = IF(@idx_exists = 0, 
    'CREATE INDEX idx_pob_billing_month ON phone_ownership_batch (billing_month)', 
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- directory_batch: billing_month index
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS 
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'directory_batch' AND INDEX_NAME = 'idx_drb_billing_month');
SET @sql = IF(@idx_exists = 0, 
    'CREATE INDEX idx_drb_billing_month ON directory_batch (billing_month)', 
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
