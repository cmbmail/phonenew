-- 系统版本管理 + 升级包
CREATE TABLE system_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version VARCHAR(50) NOT NULL COMMENT '版本号(如 1.0.0)',
    description VARCHAR(500) DEFAULT NULL COMMENT '版本描述',
    is_current TINYINT NOT NULL DEFAULT 0 COMMENT '是否当前版本 0=否 1=是',
    backup_id BIGINT DEFAULT NULL COMMENT '升级前备份ID(用于回滚)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    UNIQUE INDEX uk_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统版本记录';

CREATE TABLE version_upgrade_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_name VARCHAR(200) NOT NULL COMMENT '包文件名',
    target_version VARCHAR(50) NOT NULL COMMENT '目标版本号',
    description VARCHAR(500) DEFAULT NULL COMMENT '升级描述',
    file_path VARCHAR(500) NOT NULL COMMENT '包文件存储路径',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/APPLIED/FAILED/ROLLED_BACK',
    applied_at DATETIME NULL COMMENT '应用时间',
    error_message TEXT DEFAULT NULL COMMENT '错误信息',
    created_by BIGINT DEFAULT NULL COMMENT '上传人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    INDEX idx_target_version (target_version),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版本升级包';
