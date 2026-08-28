-- V64: Add branch_org_id to allocation_org_batch (号码分摊机构按一级分行数据隔离)

SET @exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE table_schema = DATABASE() AND table_name = 'allocation_org_batch' AND column_name = 'branch_org_id');
SET @sql = IF(@exists = 0,
    'ALTER TABLE allocation_org_batch ADD COLUMN branch_org_id BIGINT DEFAULT NULL COMMENT ''归属一级分行组织ID'' AFTER imported_by',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists2 = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'allocation_org_batch' AND index_name = 'idx_alloc_org_batch_branch');
SET @sql2 = IF(@exists2 = 0,
    'CREATE INDEX idx_alloc_org_batch_branch ON allocation_org_batch (branch_org_id, deleted_at)',
    'SELECT 1');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;