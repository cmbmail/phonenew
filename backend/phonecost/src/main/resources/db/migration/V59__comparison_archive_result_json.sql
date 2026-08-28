-- V59: Add result_json column to comparison_archive for storing full comparison snapshot
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'comparison_archive' AND column_name = 'result_json');
SET @sql = IF(@exists = 0,
    'ALTER TABLE comparison_archive ADD COLUMN result_json LONGTEXT COMMENT ''对比结果快照(JSON), 归档时全量存储, 查看时直接读取避免重算''',
    'SET @dummy = 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
