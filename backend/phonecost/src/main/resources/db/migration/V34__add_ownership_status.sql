-- V34: Add status column to phone_ownership_entry
-- 0=正常(active), 1=拆机(disconnected)
-- Column already added manually; this migration is a placeholder for schema history tracking
UPDATE system_version SET description = description WHERE 1=1;
