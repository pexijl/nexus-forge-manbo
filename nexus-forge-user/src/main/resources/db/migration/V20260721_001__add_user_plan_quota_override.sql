-- V20260721_001__add_user_plan_quota_override.sql
-- P5 Step 6: 单用户配额覆盖字段
-- JSON 格式: {"dailyTokenLimit":1000000,"requestLimit":500}
-- 解析失败时 QuotaService 降级到 role 默认 tier
--
-- 已用 IF NOT EXISTS 兼容 JPA ddl-auto=update 留下的字段。
-- COMMENT 不支持 IF NOT EXISTS;comment 已存在会抛 42710,
-- 用 DO block 包裹让脚本对"已注释过"的状态幂等。

ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_quota_override TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'users'::regclass AND a.attname = 'plan_quota_override'
    ) THEN
        COMMENT ON COLUMN users.plan_quota_override IS '单用户配额覆盖,JSON 格式';
    END IF;
END $$;
