-- V24: Update version to 1.4.1 (bill month management feature)
-- Idempotent: only update if current version is 1.4.0
UPDATE system_version SET version = '1.4.1', updated_at = NOW() WHERE version = '1.4.0';
