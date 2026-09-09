-- V77: 新增分摊机构对照表（allocation_org_mapping）
-- 一个机构名称（唯一）对应一个机构代码（唯一）、一个成本中心（唯一），多个部门全路径（、分隔，同一一级分行下唯一）
CREATE TABLE IF NOT EXISTS allocation_org_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    l1_branch VARCHAR(100) NOT NULL DEFAULT '' COMMENT '一级分行',
    org_name VARCHAR(256) NOT NULL DEFAULT '' COMMENT '机构名称（唯一）',
    org_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '机构代码（唯一）',
    cost_center_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '成本中心代码（唯一）',
    dept_full_path TEXT NULL COMMENT '部门全路径（多个以、分隔；值在同一一级分行下唯一）',
    remark VARCHAR(512) NOT NULL DEFAULT '' COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    UNIQUE INDEX uk_alloc_org_mapping_org_name (org_name),
    UNIQUE INDEX uk_alloc_org_mapping_org_code (org_code),
    UNIQUE INDEX uk_alloc_org_mapping_cost_center (cost_center_code),
    INDEX idx_alloc_org_mapping_l1_branch (l1_branch)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分摊机构对照表';

-- 版本号更新至 1.12.145
UPDATE system_version SET is_current = 0 WHERE is_current = 1 AND deleted_at IS NULL;
INSERT INTO system_version (version, description, is_current, created_at, updated_at)
SELECT '1.12.145', '基础数据新增分摊机构对照表', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.12.145' AND deleted_at IS NULL);
UPDATE system_version SET is_current = 1 WHERE version = '1.12.145' AND is_current = 0 AND deleted_at IS NULL;
