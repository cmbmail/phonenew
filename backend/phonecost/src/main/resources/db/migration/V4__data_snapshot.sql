-- V4: 数据快照表 —— 记录每个月分摊使用的号码归属批次和通讯录批次
CREATE TABLE IF NOT EXISTS data_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bill_batch_id BIGINT NOT NULL COMMENT '账单批次ID',
    ownership_batch_id BIGINT COMMENT '号码归属批次ID',
    directory_batch_id BIGINT COMMENT '通讯录批次ID',
    matched_count INT DEFAULT 0 COMMENT '匹配成功条数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    UNIQUE KEY uk_bill_batch (bill_batch_id, deleted_at),
    KEY idx_ownership_batch (ownership_batch_id),
    KEY idx_directory_batch (directory_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据快照——每月分摊使用的基础数据快照';
