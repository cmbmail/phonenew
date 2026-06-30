-- 修正默认管理员密码为 Admin@123（BCrypt hash）
-- V1 中初始密码 hash 不正确，此迁移修正
UPDATE sys_user SET password = '$2a$10$x5EiGye/p5goxhz803uSne0vzjww3dR8Erg9ZXvQNAA7lOf0d3Pbe' WHERE username = 'admin' AND id = 1;
