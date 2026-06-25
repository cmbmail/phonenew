-- Add cost_center column to allocation_result for cost-center-based allocation
ALTER TABLE allocation_result ADD COLUMN cost_center VARCHAR(50) NULL AFTER org_name;
