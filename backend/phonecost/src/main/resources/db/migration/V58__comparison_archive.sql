-- V58: Add comparison_archive table for data comparison archiving
SET @exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'comparison_archive');
SET @sql = IF(@exists = 0,
    'CREATE TABLE comparison_archive (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        compare_type VARCHAR(20) NOT NULL COMMENT ''exception=与例外对比, month=跨月对比'',
        month1 VARCHAR(10) COMMENT ''月份A (跨月对比)'',
        month2 VARCHAR(10) COMMENT ''月份B (跨月对比)'',
        latest_month VARCHAR(10) COMMENT ''最新月份 (例外对比)'',
        added_count INT DEFAULT 0,
        removed_count INT DEFAULT 0,
        changed_count INT DEFAULT 0,
        unchanged_count INT DEFAULT 0,
        total_count INT DEFAULT 0,
        archived_by BIGINT,
        remark VARCHAR(500),
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        deleted_at DATETIME NULL,
        INDEX idx_compare_type (compare_type),
        INDEX idx_created_at (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
    'SET @dummy = 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
