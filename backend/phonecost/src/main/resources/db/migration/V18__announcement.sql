-- V18: 通知公告表
-- status: 0=草稿 1=已发布 2=已归档
-- type: 0=通知 1=公告
-- priority: 0=普通 1=重要 2=紧急

CREATE TABLE IF NOT EXISTS announcement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL COMMENT '标题',
    content TEXT NOT NULL COMMENT '内容',
    type TINYINT NOT NULL DEFAULT 0 COMMENT '类型: 0=通知 1=公告',
    priority TINYINT NOT NULL DEFAULT 0 COMMENT '优先级: 0=普通 1=重要 2=紧急',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0=草稿 1=已发布 2=已归档',
    author_id BIGINT COMMENT '发布人ID',
    author_name VARCHAR(100) DEFAULT '' COMMENT '发布人姓名',
    published_at DATETIME COMMENT '发布时间',
    pinned TINYINT NOT NULL DEFAULT 0 COMMENT '置顶: 0=否 1=是',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME COMMENT '软删除时间',
    INDEX idx_announcement_status (status),
    INDEX idx_announcement_type (type),
    INDEX idx_announcement_published (published_at),
    INDEX idx_announcement_author (author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知公告';
