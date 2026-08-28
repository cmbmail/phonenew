-- V63: Add org_code_mapping table (组织机构对照表)

CREATE TABLE IF NOT EXISTS org_code_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_code VARCHAR(64) NOT NULL DEFAULT '',
    org_name VARCHAR(256) NOT NULL DEFAULT '',
    cost_center_code VARCHAR(64) NOT NULL DEFAULT '',
    remark VARCHAR(512) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    INDEX idx_org_code_mapping_org_code (org_code),
    INDEX idx_org_code_mapping_cost_center (cost_center_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;