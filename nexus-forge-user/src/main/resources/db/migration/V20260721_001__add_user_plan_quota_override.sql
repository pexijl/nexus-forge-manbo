-- V20260721_001__add_user_plan_quota_override.sql
-- P5 Step 6: 单用户配额覆盖字段
-- JSON 格式: {"dailyTokenLimit":1000000,"requestLimit":500}
-- 解析失败时 QuotaService 降级到 role 默认 tier

ALTER TABLE users ADD COLUMN plan_quota_override TEXT;
COMMENT ON COLUMN users.plan_quota_override IS '单用户配额覆盖,JSON 格式';
