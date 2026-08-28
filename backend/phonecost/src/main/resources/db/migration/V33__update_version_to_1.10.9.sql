-- V33: Update version to 1.10.9
-- DatePicker hotfix + BUG fixes release

INSERT INTO system_version (version, description, created_at)
SELECT '1.10.9', 'DatePicker hotfix + exception import OOM fix + transaction optimization + i18n fix', NOW()
WHERE NOT EXISTS (SELECT 1 FROM system_version WHERE version = '1.10.9');
