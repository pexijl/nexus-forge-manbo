-- V20260801_002__add_user_ai_preference.sql
-- AI 个性化配置:每用户一份,可选 vendor/model/加密 API Key
--
-- 表若已存在,用 IF NOT EXISTS 跳过;COMMENT 重复执行会抛 42710,用 DO block 包裹。

CREATE TABLE IF NOT EXISTS user_ai_preference (
                                    user_id              BIGINT       PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                                    vendor               VARCHAR(32)  NOT NULL,
                                    model                VARCHAR(128) NOT NULL,
                                    -- 用户私 Key:AES-256-GCM 密文(iv 12B || ciphertext || tag 16B)
                                    -- NULL = 用系统共享 Key(走 ai_global_default + yaml)
                                    encrypted_api_key    BYTEA        NULL,
                                    -- 仅展示用的 Key 指纹(原文前 4 字符 + sha256 前 8 hex),便于用户确认自己填的是哪个 Key
                                    api_key_fingerprint  VARCHAR(16)  NULL,
                                    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
                                    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_ai_pref_vendor ON user_ai_preference(vendor);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_description WHERE objoid = 'user_ai_preference'::regclass AND objsubid = 0) THEN
        COMMENT ON TABLE user_ai_preference IS 'AI 用户个性化配置(每用户独立,可选 vendor/model/私 Key)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_preference'::regclass AND a.attname = 'encrypted_api_key'
    ) THEN
        COMMENT ON COLUMN user_ai_preference.encrypted_api_key IS '用户私有 API Key 的 AES-256-GCM 密文(iv||ct||tag);NULL = 用系统共享 Key';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_preference'::regclass AND a.attname = 'api_key_fingerprint'
    ) THEN
        COMMENT ON COLUMN user_ai_preference.api_key_fingerprint IS 'Key 指纹展示(不暴露真值),形如 sk-12••••a3b4';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'user_ai_preference'::regclass AND a.attname = 'enabled'
    ) THEN
        COMMENT ON COLUMN user_ai_preference.enabled IS 'false 时该用户配置整体禁用,回退到 ai_global_default';
    END IF;
END $$;