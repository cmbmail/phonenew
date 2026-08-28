-- V60: Add allocation_org tables (号码分摊机构)

-- allocation_org_batch
CREATE TABLE IF NOT EXISTS allocation_org_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) DEFAULT '',
    total_count INT DEFAULT 0,
    billing_month VARCHAR(7) DEFAULT NULL,
    import_status TINYINT DEFAULT 0,
    error_message TEXT,
    imported_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    UNIQUE KEY uk_batch_no (batch_no, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- allocation_org_entry
CREATE TABLE IF NOT EXISTS allocation_org_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    phone_number VARCHAR(50) DEFAULT '',
    l1_branch VARCHAR(100) DEFAULT '',
    alloc_dept VARCHAR(200) DEFAULT '',
    org_code VARCHAR(50) DEFAULT '',
    cost_center VARCHAR(50) DEFAULT '',
    remark VARCHAR(500) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    INDEX idx_batch_id (batch_id),
    INDEX idx_phone_number (phone_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
