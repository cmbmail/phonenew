CREATE TABLE backup_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    backup_type VARCHAR(20) NOT NULL COMMENT 'FULL=全量备份, INCREMENTAL=增量备份',
    file_path VARCHAR(500) NOT NULL COMMENT '备份文件路径',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS/SUCCESS/FAILED',
    table_count INT NOT NULL DEFAULT 0 COMMENT '备份表数量',
    row_count BIGINT NOT NULL DEFAULT 0 COMMENT '备份行数',
    trigger_type VARCHAR(20) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO=自动, MANUAL=手动',
    error_message TEXT COMMENT '错误信息',
    base_backup_id BIGINT COMMENT '增量备份的基准全量备份ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    INDEX idx_backup_type (backup_type),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据备份记录';
