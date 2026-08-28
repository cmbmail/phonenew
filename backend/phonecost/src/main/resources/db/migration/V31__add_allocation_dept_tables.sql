-- ============================================================
-- V31: 分摊部门导入表 (allocation_dept_batch / allocation_dept_entry)
-- Idempotent: IF NOT EXISTS pattern
-- ============================================================

-- 分摊部门批次表
CREATE TABLE IF NOT EXISTS allocation_dept_batch (
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
    UNIQUE KEY uk_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 分摊部门明细表
CREATE TABLE IF NOT EXISTS allocation_dept_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    branch VARCHAR(100) DEFAULT '',
    dept_name VARCHAR(200) DEFAULT '',
    full_path VARCHAR(500) DEFAULT '',
    org_code VARCHAR(50) DEFAULT '',
    cost_center VARCHAR(50) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    INDEX idx_batch_id (batch_id),
    INDEX idx_org_code (org_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
