-- V20260902_005__add_user_ai_model_alias.sql
-- Phase 4 — 用户级 model alias(模型别名)
-- 1 user ↔ N 个 alias,每个 alias = (alias 名 → target vendor + model)
-- 用户在 chat 请求里把 model 字段填 alias 名(不带冒号),PreferenceResolver 改写为
-- "vendor:model" 再走原优先级链(系统 Key / BYOK 代理 / 默认代理 / global default)
--
-- 设计要点:
--  * 硬删策略(跟 model_catalog / vendor_config / user_ai_proxy 一致)
--  * UNIQUE (user_id, alias) — 同 user 内 alias 名唯一
--  * 不 FK 到 ai_model_catalog:admin 可在 catalog 禁用 model,alias 仍存在
--    → resolver 解析时若 target 不在 catalog 或 enabled=false,抛 LLM_MODEL_NOT_FOUND /
--    LLM_MODEL_DISABLED(沿用 Phase 1 错误码,不引入新码)
--  * ON DELETE CASCADE 到 users:user 真删时 alias 一起删(走 account_lifecycle 真删流程)
--  * alias 名限制不含冒号(避免与 "vendor:model" 格式冲突;service 层校验)

CREATE TABLE IF NOT EXISTS user_ai_model_alias (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 用户友好的别名,e.g. "我的 GPT"、"快速响应"、"国产便宜"
    -- 不含冒号(与 "vendor:model" 格式区分)
    alias           VARCHAR(64)  NOT NULL,
    -- 解析目标:alias 命中后,改写为 "target_vendor:target_model" 走原 resolver
    target_vendor   VARCHAR(32)  NOT NULL,
    target_model    VARCHAR(128) NOT NULL,
    description     TEXT,
    -- false 时 alias 跳过(fall through 到原优先级),相当于"草稿/未启用"
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_ai_model_alias_user_name UNIQUE (user_id, alias)
);

-- 列表查询索引
CREATE INDEX IF NOT EXISTS idx_user_ai_model_alias_user ON user_ai_model_alias(user_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_description WHERE objoid = 'user_ai_model_alias'::regclass AND objsubid = 0) THEN
        COMMENT ON TABLE user_ai_model_alias IS '用户级 model alias(模型别名,Phase 4):1 user ↔ N,alias 命中后改写为 target_vendor:target_model';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_model_alias'::regclass AND a.attname = 'alias'
    ) THEN
        COMMENT ON COLUMN user_ai_model_alias.alias IS '用户友好别名(同 user 内唯一,不含冒号)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_model_alias'::regclass AND a.attname = 'target_vendor'
    ) THEN
        COMMENT ON COLUMN user_ai_model_alias.target_vendor IS '解析目标 vendor(写死到 "vendor:model" 格式)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_model_alias'::regclass AND a.attname = 'enabled'
    ) THEN
        COMMENT ON COLUMN user_ai_model_alias.enabled IS 'false 时 alias 跳过(fall through 到原优先级,实现"草稿"语义)';
    END IF;
END $$;
