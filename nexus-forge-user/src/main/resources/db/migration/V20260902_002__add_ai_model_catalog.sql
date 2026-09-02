-- V20260902_002__add_ai_model_catalog.sql
-- Phase 1 多模型管理:admin 可 CRUD 的模型目录
--
-- 表若已存在(JPA ddl-auto=update 在 dev 已建过),用 IF NOT EXISTS 跳过。
-- COMMENT 重复执行会抛 42710,用 DO block + pg_description 判幂等。
--
-- 设计要点:
--   - (vendor, model_name) 唯一 — 同一 vendor 不允许重名 model
--   - enabled 字段给 admin 紧急关停(灰度 / 出问题时一键 disable)
--   - capabilities / cost 留给 Phase 4 路由决策(成本路由 / 灰度 / 限流分层)
--   - tier 字段(Phase 1 暂不过滤,Phase 2+ 可结合 user tier 限流)
--   - 不放 soft delete:model catalog 是配置数据,不是用户数据,直接真删
--     (admin 删错可重新 INSERT;真删配合审计走 account_lifecycle_log 复用)

CREATE TABLE IF NOT EXISTS ai_model_catalog (
                                   id                  BIGSERIAL     PRIMARY KEY,
                                   vendor              VARCHAR(64)   NOT NULL,
                                   model_name          VARCHAR(128)  NOT NULL,
                                   display_name        VARCHAR(128),
                                   enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
                                   context_window      INTEGER,
                                   max_output_tokens   INTEGER,
                                   supports_vision     BOOLEAN       NOT NULL DEFAULT FALSE,
                                   supports_tools      BOOLEAN       NOT NULL DEFAULT TRUE,
                                   supports_streaming  BOOLEAN       NOT NULL DEFAULT TRUE,
                                   cost_input_per_1k   DECIMAL(10, 6),
                                   cost_output_per_1k  DECIMAL(10, 6),
                                   tier                VARCHAR(32)   NOT NULL DEFAULT 'STANDARD',
                                   description         TEXT,
                                   created_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   updated_at          TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   CONSTRAINT uk_ai_model_catalog_vendor_model UNIQUE (vendor, model_name),
                                   CONSTRAINT ck_ai_model_catalog_tier CHECK (tier IN ('FREE', 'STANDARD', 'PREMIUM'))
);

-- 高频查询索引:网关运行时校验 "vendor + model 在不在 catalog + enabled 不 enabled"
-- 部分索引过滤 enabled=true 缩小体积,99% 查询走这个
CREATE INDEX IF NOT EXISTS idx_ai_model_catalog_enabled
    ON ai_model_catalog(vendor, model_name)
    WHERE enabled = TRUE;

-- 启动期 seed 不放这里:放应用代码(aiModelCatalogService.seedFromYamlIfEmpty),
-- 这样 (a) 不需要 import 复杂的 yaml 解析到 SQL,(b) 跨 profile 复用同一份 seed,
-- (c) catalog 有数据后就不再跑(幂等)。

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_description WHERE objoid = 'ai_model_catalog'::regclass AND objsubid = 0) THEN
        COMMENT ON TABLE ai_model_catalog IS 'AI 模型目录(管理员可 CRUD,Phase 1 多模型管理 source of truth)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_model_catalog'::regclass AND a.attname = 'vendor'
    ) THEN
        COMMENT ON COLUMN ai_model_catalog.vendor IS '供应商标识(openai/deepseek/ollama/anthropic/...)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_model_catalog'::regclass AND a.attname = 'model_name'
    ) THEN
        COMMENT ON COLUMN ai_model_catalog.model_name IS '上游 API 用的模型标识(如 gpt-4o-mini / deepseek-v4-flash)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_model_catalog'::regclass AND a.attname = 'display_name'
    ) THEN
        COMMENT ON COLUMN ai_model_catalog.display_name IS 'UI 友好名(Phase 4 alias 路由表之前的临时展示字段)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_model_catalog'::regclass AND a.attname = 'enabled'
    ) THEN
        COMMENT ON COLUMN ai_model_catalog.enabled IS 'admin 紧急关停(出问题时一键 disable,网关层立即拒绝)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_model_catalog'::regclass AND a.attname = 'context_window'
    ) THEN
        COMMENT ON COLUMN ai_model_catalog.context_window IS '上下文窗口大小(tokens,用于 Phase 4 限流)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_model_catalog'::regclass AND a.attname = 'tier'
    ) THEN
        COMMENT ON COLUMN ai_model_catalog.tier IS '限流分层(FREE/STANDARD/PREMIUM,Phase 1 暂不过滤)';
    END IF;
END $$;
