-- V26: Add department ownership fields to directory_entry (for 115 upgrade)
-- These columns already exist on 240 (added manually), skip via flyway_schema_history insert

ALTER TABLE directory_entry ADD COLUMN alloc_dept VARCHAR(255) DEFAULT '' AFTER dept_path;
ALTER TABLE directory_entry ADD COLUMN org_code VARCHAR(64) DEFAULT '' AFTER alloc_dept;
ALTER TABLE directory_entry ADD COLUMN cost_center VARCHAR(64) DEFAULT '' AFTER org_code;
ALTER TABLE directory_entry ADD COLUMN remark VARCHAR(500) DEFAULT '' AFTER cost_center;

-- Update version to 1.5.0
UPDATE system_version SET version = '1.5.0', updated_at = NOW() WHERE id = 1;
