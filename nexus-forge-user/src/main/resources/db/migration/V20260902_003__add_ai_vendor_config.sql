-- V20260902_003__add_ai_vendor_config.sql
-- Phase 2 多模型管理:vendor base_url + enabled 持久化(admin 可改)
--
-- 一 vendor 一行(vendor UNIQUE),soft delete 模式:delete_at + BaseEntity
-- 风格的 @SQLDelete 拦截(实体类上)。
--
-- 跟 model catalog 的关系:
--   - ai_vendor_config 控制"vendor 是否可用" + "vendor 的 base URL 是啥"
--   - ai_model_catalog 控制"具体 model 是否可用" + "model 的元信息"
--   - 网关运行时:vendor enabled=false → 所有该 vendor 的 model 都不可用;
--     vendor enabled=true 但具体 model 在 catalog enabled=false → 只该 model 不可用。
--
-- 数据流:
--   1. 首次启动 seed runner 把 yaml 的 spring.ai.providers.*.base-url / enabled
--      拷到本表(ON CONFLICT DO NOTHING 幂等)
--   2. 之后 DB 是 source of truth;admin 通过 PUT /api/admin/ai/vendors/{vendor} 改
--   3. 私 Key 路径(VendorChatModelFactory)从 DB 读,fallback yaml
--   4. 系统 Key 路径(OpenAI starter bean)仍是启动期从 yaml 装;
--      Phase 2 暂不重建 bean,系统 Key 改 base_url 需要重启或等 Phase 4 hot reload

CREATE TABLE IF NOT EXISTS ai_vendor_config (
                                    id          BIGSERIAL     PRIMARY KEY,
                                    vendor      VARCHAR(64)   NOT NULL UNIQUE,
                                    base_url    VARCHAR(512)  NOT NULL,
                                    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
                                    description TEXT,
                                    created_at  TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 高频查询索引:私 Key 路径按 vendor 查配置(走 UNIQUE 已经覆盖)
-- 但跨 vendor 列表(GET /api/admin/ai/vendors)按 vendor 排序,加一个排序友好索引
CREATE INDEX IF NOT EXISTS idx_ai_vendor_config_vendor
    ON ai_vendor_config(vendor);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_description WHERE objoid = 'ai_vendor_config'::regclass AND objsubid = 0) THEN
        COMMENT ON TABLE ai_vendor_config IS 'AI vendor 配置(base_url + enabled),admin 可改;私 Key 路径读这里';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_vendor_config'::regclass AND a.attname = 'vendor'
    ) THEN
        COMMENT ON COLUMN ai_vendor_config.vendor IS '供应商标识(同 ai_model_catalog.vendor);UNIQUE 一行一 vendor';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_vendor_config'::regclass AND a.attname = 'base_url'
    ) THEN
        COMMENT ON COLUMN ai_vendor_config.base_url IS 'OpenAI 兼容协议 base URL(Anthropic 不需要,空字符串)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_vendor_config'::regclass AND a.attname = 'enabled'
    ) THEN
        COMMENT ON COLUMN ai_vendor_config.enabled IS 'false 时该 vendor 整体被网关拒绝(私 Key 路径直接抛 LLM_CONFIG_MISSING)';
    END IF;
END $$;
