-- V28: Add billing_month to recording_data_batch
ALTER TABLE recording_data_batch ADD COLUMN billing_month VARCHAR(7) DEFAULT '' AFTER batch_no;

-- Update version to 1.8.0
UPDATE system_version SET version = '1.8.0', updated_at = NOW() WHERE id = 1 AND version < '1.8.0';
