-- V20260902_004__add_user_ai_proxy.sql
-- Phase 3 — 用户级 BYOK(多代理端点)
-- 1 user ↔ N 个独立 AI 代理端点(每个有独立 base_url + api_key + 可选 default_model)
-- 用户可标记其中一个为 is_default,代表"当前活跃代理" = 偏好绑定
--
-- 设计要点:
--  * 硬删策略(跟 model_catalog / vendor_config 一致:配置数据,不走软删)
--  * UNIQUE (user_id, name) — 用户内 alias 唯一
--  * Partial unique index (user_id) WHERE is_default = TRUE — 每用户最多 1 个默认
--  * 不 FK 到 ai_vendor_config:vendor 可被独立禁用/删除,runtime 检查更灵活
--  * encrypted_api_key 必填:BYOK 场景不允许"建了代理但没 Key"
--  * ON DELETE CASCADE 到 users:user 真删时代理一起删(走 account_lifecycle 的真删流程)

CREATE TABLE IF NOT EXISTS user_ai_proxy (
    id                     BIGSERIAL    PRIMARY KEY,
    user_id                BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- 用户自定义别名(同 user 内唯一),e.g. "我的 DeepSeek 中转"、"公司 OpenAI"
    name                   VARCHAR(64)  NOT NULL,
    -- OpenAI 协议家族 vendor(openai / deepseek / dashscope / glm / kimi / doubao / hunyuan /
    -- siliconflow / oneapi / openrouter / minimax 等),由 AiVendorRegistry 校验
    vendor                 VARCHAR(32)  NOT NULL,
    -- 独立 base URL(覆盖 ai_vendor_config / yaml 同 vendor 的 base URL)
    base_url               VARCHAR(512) NOT NULL,
    -- AES-256-GCM(iv 12B || ciphertext || tag 16B)密文
    -- 必填:BYOK 场景下"建代理不带 Key"没意义,直接拒绝
    encrypted_api_key      BYTEA        NOT NULL,
    -- Key 指纹(原文前 4 字符 + sha256 前 8 hex),UI 列表展示用,不暴露真值
    api_key_fingerprint    VARCHAR(16)  NOT NULL,
    -- 可选:该 proxy 默认 model(留空走 yaml 的 spring.ai.providers.<vendor>.default-model)
    default_model          VARCHAR(128),
    -- false 时该 proxy 整体被网关拒绝;user 可在 UI 一键开关
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    -- true 时表示"用户的当前活跃代理";每用户最多 1 个,partial unique 兜底 + app 层事务强制
    is_default             BOOLEAN      NOT NULL DEFAULT FALSE,
    description            TEXT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_ai_proxy_user_name UNIQUE (user_id, name)
);

-- 列表查询索引(GET /api/ai/proxies 走 user_id)
CREATE INDEX IF NOT EXISTS idx_user_ai_proxy_user ON user_ai_proxy(user_id);

-- 唯一索引:每用户最多 1 个 is_default = TRUE
-- partial unique index 是 PG 9.0+ 特性,app 层 setDefault 事务里也会 unmark others
-- (双层防御:DB 防并发竞争,app 防逻辑错乱)
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_ai_proxy_one_default
    ON user_ai_proxy(user_id) WHERE is_default = TRUE;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_description WHERE objoid = 'user_ai_proxy'::regclass AND objsubid = 0) THEN
        COMMENT ON TABLE user_ai_proxy IS '用户级 AI 代理端点(BYOK 多端点,1 user ↔ N,is_default 标记活跃代理)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_proxy'::regclass AND a.attname = 'name'
    ) THEN
        COMMENT ON COLUMN user_ai_proxy.name IS '用户自定义别名(同 user 内唯一)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_proxy'::regclass AND a.attname = 'base_url'
    ) THEN
        COMMENT ON COLUMN user_ai_proxy.base_url IS '独立 base URL(覆盖 vendor 默认)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_proxy'::regclass AND a.attname = 'encrypted_api_key'
    ) THEN
        COMMENT ON COLUMN user_ai_proxy.encrypted_api_key IS 'API Key AES-256-GCM 密文(iv||ct||tag)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_proxy'::regclass AND a.attname = 'is_default'
    ) THEN
        COMMENT ON COLUMN user_ai_proxy.is_default IS 'true = 用户当前活跃代理(每用户最多 1)';
    END IF;
END $$;
