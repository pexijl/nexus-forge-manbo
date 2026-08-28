-- V20260801_001__add_ai_global_default.sql
-- AI 个性化配置:全局默认 vendor/model(单行,管理员可改,所有用户共享 Key 免费模式)
--
-- 表若已存在(已被 JPA ddl-auto=update 建过),用 IF NOT EXISTS 跳过。
-- COMMENT 重复执行会抛 42710,用 DO block + pg_description 判幂等。

CREATE TABLE IF NOT EXISTS ai_global_default (
                                   id          INT          PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                                   vendor      VARCHAR(32)  NOT NULL,
                                   model       VARCHAR(128) NOT NULL,
                                   enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
                                   created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 单行种子数据:vendor=qwen 但 model=__UNSET__ 表示"未配置全局默认 model"
-- 启动后管理员必须先调 PUT /api/admin/ai/global-default 设真实 model,
-- 否则任何 system 模式调用会被 PreferenceResolver 拒绝(LLM_GLOBAL_DEFAULT_NOT_CONFIGURED)。
-- 显式给所有 NOT NULL 字段传值(created_at / updated_at 显式传 now()),兼容
-- JPA ddl-auto=update 建的"无 DEFAULT"版本;ON CONFLICT 保证重复执行也幂等。
INSERT INTO ai_global_default (id, vendor, model, enabled, created_at, updated_at)
VALUES (1, 'qwen', '__UNSET__', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- COMMENT 幂等:用 DO block 包裹,确保 "表/列已存在注释" 时不再重复设置
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_description WHERE objoid = 'ai_global_default'::regclass AND objsubid = 0) THEN
        COMMENT ON TABLE ai_global_default IS 'AI 全局默认 vendor/model(单行,所有人共用 Key 的免费模式)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_global_default'::regclass AND a.attname = 'id'
    ) THEN
        COMMENT ON COLUMN ai_global_default.id IS '永远 = 1,单行表';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_global_default'::regclass AND a.attname = 'vendor'
    ) THEN
        COMMENT ON COLUMN ai_global_default.vendor IS '默认供应商(openai/deepseek/qwen/ollama/anthropic)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_global_default'::regclass AND a.attname = 'model'
    ) THEN
        COMMENT ON COLUMN ai_global_default.model IS '默认模型标识(如 qwen-turbo)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_global_default'::regclass AND a.attname = 'enabled'
    ) THEN
        COMMENT ON COLUMN ai_global_default.enabled IS 'false 时拒绝所有未配私 Key 的用户调用';
    END IF;
END $$;