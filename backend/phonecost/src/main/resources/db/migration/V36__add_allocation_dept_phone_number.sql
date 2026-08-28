-- Add phone_number column to allocation_dept_entry
ALTER TABLE allocation_dept_entry ADD COLUMN phone_number VARCHAR(50) DEFAULT '';
CREATE INDEX idx_alloc_dept_phone_number ON allocation_dept_entry (phone_number);

-- Update version
INSERT INTO system_version (version, description, created_at, updated_at)
SELECT '1.11.0', '新增部门归属号码列', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.11.0');
