-- V14: Security hardening
-- 1. Force password change for all existing users (they may have weak/default passwords)
--    After this migration, every user must change their password on next login.
--    New users created with strong passwords by admins can have must_change_pwd=0 set explicitly.

UPDATE sys_user SET must_change_pwd = 1 WHERE deleted_at IS NULL AND must_change_pwd = 0;
