-- V20260628_001__add_avatar_key.sql
-- 加 IF NOT EXISTS 兼容:JPA ddl-auto=update 在切换到 Flyway 之前已建过该列,直接 ALTER 会失败。
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_key VARCHAR(500);
