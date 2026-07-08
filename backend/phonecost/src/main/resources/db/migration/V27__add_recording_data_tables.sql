-- V27: Add recording_data tables for 录音数据 feature
CREATE TABLE IF NOT EXISTS recording_data_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(64) NOT NULL UNIQUE,
    file_name VARCHAR(500) DEFAULT '',
    total_count INT DEFAULT 0,
    import_status TINYINT DEFAULT 0,
    error_message TEXT,
    imported_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recording_data_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    extension VARCHAR(64) NOT NULL DEFAULT '',
    phone_number VARCHAR(64) NOT NULL DEFAULT '',
    dept_name VARCHAR(255) DEFAULT '',
    remark VARCHAR(500) DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    INDEX idx_batch_id (batch_id),
    INDEX idx_extension (extension),
    INDEX idx_phone_number (phone_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE system_version SET version = '1.6.0', updated_at = NOW() WHERE id = 1;
