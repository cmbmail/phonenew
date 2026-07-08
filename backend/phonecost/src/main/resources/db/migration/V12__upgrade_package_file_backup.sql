-- 版本升级包增加文件备份路径字段，用于回滚时恢复前端dist和后端JAR
ALTER TABLE version_upgrade_package
    ADD COLUMN frontend_backup_path VARCHAR(500) NULL COMMENT '升级前前端dist备份路径',
    ADD COLUMN backend_backup_path VARCHAR(500) NULL COMMENT '升级前后端JAR备份路径';
