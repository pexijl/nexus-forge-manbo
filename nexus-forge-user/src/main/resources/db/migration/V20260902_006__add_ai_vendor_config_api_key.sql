-- V20260902_006__add_ai_vendor_config_api_key.sql
-- Phase 6 系统 Key 轮换:把 vendor 的系统 API key 持久化到 ai_vendor_config,
-- 管理员可通过 PUT /api/admin/ai/vendors/{vendor}/api-key 在线热轮换,无需重启。
--
-- 加密方式:AES-256-GCM(跟 user_ai_proxy.encrypted_api_key / user_ai_preference 统一),
-- 密钥由 spring.ai.preference.master-key 派生 → jwt.secret → 测试兜底,见 ApiKeyCipher。
-- 密文布局:iv(12B) || ciphertext || tag(16B),存 BYTEA。
--
-- 跟 base_url 的关系:
--   - base_url 已在 V20260902_003 进 DB(Phase 2)
--   - api_key 此前一直留在 yaml(application.yaml 的 spring.ai.providers.<v>.api-key),
--     改完需重启,Phase 5 通过 SystemKeyChatModelFactory 解决 baseUrl 热重建,
--     但 apiKey 仍走 yaml。本迁移把 apiKey 也 DB 化 → Phase 6 全量闭环
--   - DB encrypted_api_key IS NULL → 走 yaml 兜底(同 baseUrl 的 DB 优先 + yaml fallback 模式)
--   - DB encrypted_api_key NOT NULL → 走 DB,改完立即生效(事件清 SystemKeyChatModelFactory cache)
--
-- 字段选用:
--   encrypted_api_key BYTEA — AES-GCM 密文;nullable 是关键(yaml 兜底路径)
--   api_key_fingerprint VARCHAR(16) — UI 展示用,形如 "sk-1••••a3b4c5d6",不暴露真值
--   两列必须协同:encrypted_api_key 存在时 fingerprint 也必存在(完整性),但反过来不一定

ALTER TABLE ai_vendor_config
    ADD COLUMN IF NOT EXISTS encrypted_api_key BYTEA,
    ADD COLUMN IF NOT EXISTS api_key_fingerprint VARCHAR(16);

-- 完整性约束:有密文就必须有指纹(同 ApiKeyCipher.encrypt 行为),
-- 但 fingerprint 单独存在是合法的(老数据 / 异常场景)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ai_vendor_config_apikey_integrity'
    ) THEN
        ALTER TABLE ai_vendor_config
            ADD CONSTRAINT ai_vendor_config_apikey_integrity
            CHECK ((encrypted_api_key IS NULL) OR (api_key_fingerprint IS NOT NULL));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_vendor_config'::regclass AND a.attname = 'encrypted_api_key'
    ) THEN
        COMMENT ON COLUMN ai_vendor_config.encrypted_api_key IS
            'API Key AES-256-GCM 密文(iv 12B || ciphertext || tag 16B);NULL 时系统 Key 路径回退 yaml。Phase 6 引入';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_vendor_config'::regclass AND a.attname = 'api_key_fingerprint'
    ) THEN
        COMMENT ON COLUMN ai_vendor_config.api_key_fingerprint IS
            'API Key 指纹,形如 sk-1••••a3b4c5d6;只用于 UI 展示,不参与鉴权';
    END IF;
    -- 表注释更新
    IF NOT EXISTS (
        SELECT 1 FROM pg_description
        WHERE objoid = 'ai_vendor_config'::regclass AND objsubid = 0
          AND description LIKE '%encrypted_api_key%'
    ) THEN
        COMMENT ON TABLE ai_vendor_config IS
            'AI vendor 配置(base_url + enabled + 可选 system api_key),admin 可改;私 Key 路径读这里,系统 Key 路径 Phase 6 起也走这里';
    END IF;
END $$;
