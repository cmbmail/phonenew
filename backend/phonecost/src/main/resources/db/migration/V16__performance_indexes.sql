-- V16: Performance indexes for frequently queried tables
-- All indexes use IF NOT EXISTS / WHERE NOT EXISTS for idempotency

-- 1. bill_detail: composite index for the most common query pattern (batch + sheet_type + org_id)
-- Eliminates full table scan when filtering by batch, sheet type, and org
SELECT IF(COUNT(*) = 0, 1, 0) INTO @exists FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'bill_detail' AND index_name = 'idx_bill_detail_batch_sheet_org';
SET @sql = IF(@exists, 'CREATE INDEX idx_bill_detail_batch_sheet_org ON bill_detail(batch_id, sheet_type, org_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. bill_detail: composite index for phone number lookup within a batch
SELECT IF(COUNT(*) = 0, 1, 0) INTO @exists FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'bill_detail' AND index_name = 'idx_bill_detail_batch_phone';
SET @sql = IF(@exists, 'CREATE INDEX idx_bill_detail_batch_phone ON bill_detail(batch_id, phone_number)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. allocation_result: composite index for dashboard statistics
SELECT IF(COUNT(*) = 0, 1, 0) INTO @exists FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'allocation_result' AND index_name = 'idx_alloc_result_batch_confirm';
SET @sql = IF(@exists, 'CREATE INDEX idx_alloc_result_batch_confirm ON allocation_result(batch_id, confirm_status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. directory_entry: org_id index for data-scoped pagination
SELECT IF(COUNT(*) = 0, 1, 0) INTO @exists FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'directory_entry' AND index_name = 'idx_directory_entry_org_id';
SET @sql = IF(@exists, 'CREATE INDEX idx_directory_entry_org_id ON directory_entry(org_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. sys_organization: code index for cost-center lookups during import
SELECT IF(COUNT(*) = 0, 1, 0) INTO @exists FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'sys_organization' AND index_name = 'idx_org_code';
SET @sql = IF(@exists, 'CREATE INDEX idx_org_code ON sys_organization(code)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6. phone_ownership_entry: composite index for scoped queries
SELECT IF(COUNT(*) = 0, 1, 0) INTO @exists FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'phone_ownership_entry' AND index_name = 'idx_ownership_entry_batch_org';
SET @sql = IF(@exists, 'CREATE INDEX idx_ownership_entry_batch_org ON phone_ownership_entry(batch_id, org_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 7. audit_log: username index for audit log search
SELECT IF(COUNT(*) = 0, 1, 0) INTO @exists FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'audit_log' AND index_name = 'idx_audit_log_username';
SET @sql = IF(@exists, 'CREATE INDEX idx_audit_log_username ON audit_log(username)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 8. system_version: is_current index for version lookup
SELECT IF(COUNT(*) = 0, 1, 0) INTO @exists FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'system_version' AND index_name = 'idx_system_version_is_current';
SET @sql = IF(@exists, 'CREATE INDEX idx_system_version_is_current ON system_version(is_current)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
