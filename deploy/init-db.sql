-- ============================================================
-- 银行内部电话费用分摊系统 - Flyway DDL
-- V1__init_schema.sql
-- 2026-06-22
-- ============================================================

-- 1. 组织架构
CREATE TABLE sys_organization (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(200) NOT NULL COMMENT '组织名称',
    type        TINYINT      NOT NULL DEFAULT 0 COMMENT '类型: 1=集团 2=一级分行 3=二级分行 4=部门',
    code        VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '组织代码(部门代码)',
    parent_id   BIGINT       NULL COMMENT '上级组织ID',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序序号',
    path        VARCHAR(500) NOT NULL DEFAULT '' COMMENT '物化路径(如/1/5/23/101/)',
    is_active   TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用: 0=停用 1=启用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME     NULL COMMENT '软删除时间',
    INDEX idx_parent_id (parent_id),
    INDEX idx_type (type),
    INDEX idx_path (path(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织架构';

-- 2. 系统用户
CREATE TABLE sys_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(100) NOT NULL COMMENT '用户名',
    password        VARCHAR(255) NOT NULL COMMENT '密码(BCrypt)',
    real_name       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '真实姓名',
    role            TINYINT      NOT NULL DEFAULT 4 COMMENT '角色: 1=集团管理员 2=分行管理员 3=部门管理员 4=财务人员',
    org_id          BIGINT       NULL COMMENT '所属组织ID',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0=停用 1=启用',
    must_change_pwd TINYINT      NOT NULL DEFAULT 0 COMMENT '是否需要修改密码: 0=否 1=是',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME     NULL,
    UNIQUE INDEX uk_username (username),
    INDEX idx_org_id (org_id),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户';

-- 3. 号码归属导入批次
CREATE TABLE phone_ownership_batch (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no       VARCHAR(50)  NOT NULL COMMENT '批次号',
    file_name      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '原始文件名',
    total_count    INT          NOT NULL DEFAULT 0 COMMENT '总号码数',
    exception_count INT         NOT NULL DEFAULT 0 COMMENT '例外号码数',
    import_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '导入状态: 0=处理中 1=成功 2=失败',
    error_message  TEXT         NULL COMMENT '错误信息',
    imported_by    BIGINT       NOT NULL COMMENT '导入人用户ID',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME     NULL,
    UNIQUE INDEX uk_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='号码归属导入批次';

-- 4. 号码归属明细
CREATE TABLE phone_ownership_entry (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id    BIGINT       NOT NULL COMMENT '批次ID',
    phone_number VARCHAR(30) NOT NULL COMMENT '外线号码',
    description  VARCHAR(500) NOT NULL DEFAULT '' COMMENT '描述(用/分隔组织层级)',
    is_exception TINYINT     NOT NULL DEFAULT 0 COMMENT '是否例外: 0=否 1=是([例外]前缀)',
    org_id       BIGINT      NULL COMMENT '匹配到的组织ID',
    match_level  VARCHAR(2)  NOT NULL DEFAULT '' COMMENT '匹配级别: P0/P1/P2/P3',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at   DATETIME    NULL,
    INDEX idx_batch_id (batch_id),
    INDEX idx_phone_number (phone_number),
    INDEX idx_is_exception (is_exception)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='号码归属明细';

-- 5. 通讯录导入批次
CREATE TABLE directory_batch (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no       VARCHAR(50)  NOT NULL COMMENT '批次号',
    file_name      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '原始文件名',
    total_count    INT          NOT NULL DEFAULT 0 COMMENT '总条数',
    seconded_count INT          NOT NULL DEFAULT 0 COMMENT '借调人数',
    import_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '导入状态: 0=处理中 1=成功 2=失败',
    error_message  TEXT         NULL COMMENT '错误信息',
    imported_by    BIGINT       NOT NULL COMMENT '导入人用户ID',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME     NULL,
    UNIQUE INDEX uk_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通讯录导入批次';

-- 6. 通讯录明细
CREATE TABLE directory_entry (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id      BIGINT       NOT NULL COMMENT '批次ID',
    dept_path     VARCHAR(500) NOT NULL DEFAULT '' COMMENT '部门全路径(用-分隔)',
    username      VARCHAR(100) NOT NULL DEFAULT '' COMMENT '用户名称(员工ID)',
    extension     VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '分机号码',
    phone_number  VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '外线号码',
    org_id        BIGINT       NULL COMMENT '匹配到的组织ID(编制部门)',
    is_seconded   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否借调: 0=否 1=是',
    actual_org_id BIGINT       NULL COMMENT '实际工作部门ID(借调调整后)',
    seconded_keyword VARCHAR(50) NOT NULL DEFAULT '' COMMENT '触发的借调关键词',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at    DATETIME     NULL,
    INDEX idx_batch_id (batch_id),
    INDEX idx_phone_number (phone_number),
    INDEX idx_is_seconded (is_seconded)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通讯录明细';

-- 7. 账单解析模板
CREATE TABLE bill_template (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL COMMENT '模板名称',
    operator        VARCHAR(50)  NOT NULL DEFAULT 'CHINA_TELECOM' COMMENT '运营商',
    sheet_configs   JSON         NOT NULL COMMENT 'Sheet配置JSON数组(sheetNamePattern/phoneColumn/feeMappings/isQuarterly/skipRows)',
    is_active       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用: 0=否 1=是',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME     NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单解析模板';

-- 8. 账单导入批次
CREATE TABLE bill_batch (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no       VARCHAR(50)  NOT NULL COMMENT '批次号',
    billing_month  VARCHAR(7)   NOT NULL COMMENT '账期月份(YYYY-MM)',
    file_name      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '原始文件名',
    template_id    BIGINT       NOT NULL COMMENT '使用的模板ID',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0=DRAFT 1=ALLOCATED 2=CONFIRMED 3=LOCKED',
    total_amount   DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '总金额',
    total_count    INT          NOT NULL DEFAULT 0 COMMENT '总号码数',
    import_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '导入状态: 0=处理中 1=成功 2=失败',
    error_message  TEXT         NULL COMMENT '错误信息',
    imported_by    BIGINT       NOT NULL COMMENT '导入人用户ID',
    confirmed_at   DATETIME     NULL COMMENT '确认时间',
    confirmed_by   BIGINT       NULL COMMENT '确认人',
    locked_at      DATETIME     NULL COMMENT '锁定时间',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at     DATETIME     NULL,
    UNIQUE INDEX uk_batch_no (batch_no),
    INDEX idx_billing_month (billing_month),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单导入批次';

-- 9. 账单明细
CREATE TABLE bill_detail (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT       NOT NULL COMMENT '批次ID',
    phone_number    VARCHAR(30)  NOT NULL COMMENT '外线号码',
    extension       VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '分机号(录音/彩铃Sheet有)',
    sheet_type      VARCHAR(20)  NOT NULL DEFAULT 'CALL' COMMENT 'Sheet类型: CALL/RECORDING/CRBT/FLASH_MSG',
    monthly_rent    DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '月租(平台使用费+码号月租费)',
    call_fee        DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '通话费(国内+国际)',
    recording_fee   DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '录音费',
    crbt_fee        DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '彩铃费',
    flash_msg_fee   DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '闪信费(季度)',
    total_fee       DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '合计',
    ownership_source VARCHAR(2)  NOT NULL DEFAULT '' COMMENT '归属来源: P0/P1/P2/P3',
    is_exception    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否例外: 0=否 1=是',
    is_seconded     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否借调: 0=否 1=是',
    org_id          BIGINT       NULL COMMENT '归属组织ID',
    flash_month     VARCHAR(7)   NOT NULL DEFAULT '' COMMENT '闪信月份(仅闪信Sheet)',
    raw_data        JSON         NULL COMMENT '原始行数据(JSON,用于审计追溯)',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME     NULL,
    INDEX idx_batch_id (batch_id),
    INDEX idx_phone_number (phone_number),
    INDEX idx_org_id (org_id),
    INDEX idx_sheet_type (sheet_type),
    INDEX idx_ownership_source (ownership_source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单明细';

-- 10. 分摊结果
CREATE TABLE allocation_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT       NOT NULL COMMENT '批次ID',
    org_id          BIGINT       NOT NULL COMMENT '组织ID',
    org_name        VARCHAR(200) NOT NULL DEFAULT '' COMMENT '组织名称(冗余)',
    monthly_rent    DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '月租',
    call_fee        DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '通话费',
    recording_fee   DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '录音费',
    crbt_fee        DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '彩铃费',
    flash_msg_fee   DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '闪信费',
    total_fee       DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '合计',
    phone_count     INT          NOT NULL DEFAULT 0 COMMENT '号码数量',
    confirm_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '确认状态: 0=PENDING 1=CONFIRMED 2=WITHDRAWN',
    confirmed_at    DATETIME     NULL COMMENT '确认时间',
    confirmed_by    BIGINT       NULL COMMENT '确认人',
    withdrawn_at    DATETIME     NULL COMMENT '撤回时间',
    withdrawn_by    BIGINT       NULL COMMENT '撤回人',
    withdraw_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '撤回原因',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME     NULL,
    UNIQUE INDEX uk_batch_org (batch_id, org_id),
    INDEX idx_batch_id (batch_id),
    INDEX idx_org_id (org_id),
    INDEX idx_confirm_status (confirm_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分摊结果';

-- 11. 分摊调整记录
CREATE TABLE allocation_adjustment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id        BIGINT       NOT NULL COMMENT '批次ID',
    phone_number    VARCHAR(30)  NOT NULL COMMENT '调整号码',
    from_org_id     BIGINT       NOT NULL COMMENT '原组织ID',
    to_org_id       BIGINT       NOT NULL COMMENT '目标组织ID',
    from_org_name   VARCHAR(200) NOT NULL DEFAULT '' COMMENT '原组织名称',
    to_org_name     VARCHAR(200) NOT NULL DEFAULT '' COMMENT '目标组织名称',
    amount          DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '调整金额',
    fee_type        VARCHAR(20)  NOT NULL DEFAULT 'TOTAL' COMMENT '费用类型',
    reason          VARCHAR(500) NOT NULL DEFAULT '' COMMENT '调整原因',
    adjusted_by     BIGINT       NOT NULL COMMENT '操作人ID',
    adjusted_name   VARCHAR(100) NOT NULL DEFAULT '' COMMENT '操作人姓名',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at      DATETIME     NULL,
    INDEX idx_batch_id (batch_id),
    INDEX idx_phone_number (phone_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分摊调整记录';

-- 12. 审计日志
CREATE TABLE audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL COMMENT '操作用户ID',
    username    VARCHAR(100) NOT NULL DEFAULT '' COMMENT '用户名',
    action      VARCHAR(50)  NOT NULL COMMENT '操作类型(IMPORT/CONFIRM/WITHDRAW/ADJUST/EXPORT等)',
    entity_type VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '实体类型',
    entity_id   BIGINT       NULL COMMENT '实体ID',
    detail      JSON         NULL COMMENT '操作详情(JSON)',
    ip_address  VARCHAR(50)  NOT NULL DEFAULT '' COMMENT 'IP地址',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_action (action),
    INDEX idx_entity (entity_type, entity_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志';

-- ============================================================
-- 初始数据
-- ============================================================

-- 插入根组织(集团)
INSERT INTO sys_organization (name, type, code, parent_id, path) VALUES ('集团总部', 1, 'ROOT', NULL, '/1/');

-- 插入默认账单模板
INSERT INTO bill_template (name, operator, sheet_configs) VALUES (
    '中国电信标准模板', 'CHINA_TELECOM',
    '[
        {"sheetNamePattern":"按号码费用$", "phoneColumn":"A", "feeMappings":{"E":"monthly_rent_platform","F":"monthly_rent_code","J":"call_fee_domestic","K":"call_fee_international","L":"total_fee"}, "isQuarterly":false, "skipRows":1},
        {"sheetNamePattern":"录音$", "phoneColumn":"B", "feeMappings":{"G":"recording_fee"}, "isQuarterly":false, "skipRows":1},
        {"sheetNamePattern":"彩铃$", "phoneColumn":"B", "feeMappings":{"C":"crbt_fee"}, "isQuarterly":false, "skipRows":1},
        {"sheetNamePattern":"闪信$", "phoneColumn":"A", "feeMappings":{"D":"flash_msg_fee"}, "isQuarterly":true, "skipRows":1}
    ]'
);

-- 插入默认管理员(admin/admin123)
INSERT INTO sys_user (username, password, real_name, role, org_id, status, must_change_pwd) VALUES (
    'admin', '$2a$10$EqKcp1WFKV3M0U6zBQ7zOeGQO.HqLGxQJXQfN0H3YQJXQfN0H3YQJ', '系统管理员', 1, 1, 1, 1
);
