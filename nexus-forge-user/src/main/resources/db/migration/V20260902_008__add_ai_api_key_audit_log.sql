-- V20260902_008__add_ai_api_key_audit_log.sql
-- Phase 8 — API Key 轮换审计:每次 admin 改 / 清空 vendor 的 system apiKey,
-- 写一条审计记录,记录"谁在何时换了哪个 vendor、改前改后 fingerprint、来源 IP"。
--
-- 设计:
--   - 单表(无 FK,审计表不该被业务表删了级联丢)
--   - 跟 account_lifecycle_log 同一模式:action / actor_id / actor_role /
--     reason / metadata(JSONB) / created_at,便于将来统一定位审计入口
--   - 不暴露密文 / 明文 — metadata 只存 fingerprint 摘要(改前/改后),
--     通过对比 fingerprint_before vs fingerprint_after 可以推断出
--     "是新装、还是轮换、还是清空"
--   - actor_role 枚举由应用层校验(Java enum),DB 层只校验 NOT NULL
--
-- 写路径(VendorConfigService.setApiKey / clearApiKey):
--   1. 业务写 DB(已存在)
--   2. 发 VendorConfigChangedEvent(已存在)
--   3. **新**:同步写 ai_api_key_audit_log(同事务,失败 log warn 不阻塞)
--
-- 跟 account_lifecycle_log 的差异:
--   - 表名不同,作用域独立(ai 模块审计 vs 账号生命周期审计)
--   - vendor 字段进 metadata(灵活,可扩 ip / fingerprint 派生字段),
--     不进顶层列(查询场景"按 vendor 过滤"少,需要时可用 JSONB 索引)
--   - 后续如需高频按 vendor 查,可加 GIN 索引:
--     CREATE INDEX idx_ai_api_key_audit_log_metadata_vendor
--     ON ai_api_key_audit_log((metadata->>'vendor'));

CREATE TABLE IF NOT EXISTS ai_api_key_audit_log (
    id           BIGSERIAL PRIMARY KEY,
    action       VARCHAR(32)  NOT NULL,
    actor_id     BIGINT,
    actor_role   VARCHAR(16)  NOT NULL,
    reason       TEXT,
    metadata     JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 索引:
-- 1. 按时间查(最新 N 条 / 时间窗审计) — 跟 account_lifecycle_log 一致
CREATE INDEX IF NOT EXISTS idx_ai_api_key_audit_log_created_at
    ON ai_api_key_audit_log(created_at DESC);

-- 2. 按 action 过滤(统计 SET / CLEAR 频率)— 跟 account_lifecycle_log 一致
CREATE INDEX IF NOT EXISTS idx_ai_api_key_audit_log_action
    ON ai_api_key_audit_log(action);

-- 3. 按 actor_id 查(某 admin 改过哪些 vendor 的 key)
CREATE INDEX IF NOT EXISTS idx_ai_api_key_audit_log_actor_id
    ON ai_api_key_audit_log(actor_id);

-- GIN 索引(metadata->>'vendor'):运营场景"某 vendor 的所有 key 变更历史"高频
-- 一次性建上,免得运营查慢;GIN 对 ->> JSONB text 提取是标准用法
CREATE INDEX IF NOT EXISTS idx_ai_api_key_audit_log_metadata_vendor
    ON ai_api_key_audit_log((metadata->>'vendor'));

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_description WHERE objoid = 'ai_api_key_audit_log'::regclass AND objsubid = 0) THEN
        COMMENT ON TABLE ai_api_key_audit_log IS
            'AI vendor 系统 API Key 轮换审计(Phase 8):每次 admin SET / CLEAR 写一条,metadata 含 vendor + fingerprint_before/after + request_ip;不改 ai_vendor_config,只追加';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_api_key_audit_log'::regclass AND a.attname = 'action'
    ) THEN
        COMMENT ON COLUMN ai_api_key_audit_log.action IS
            '审计动作:SET(设置/轮换 system apiKey) / CLEAR(清空回退 yaml 兜底);Phase 8 范围,后续可扩 READ / DECRYPT_FAILED';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_api_key_audit_log'::regclass AND a.attname = 'actor_id'
    ) THEN
        COMMENT ON COLUMN ai_api_key_audit_log.actor_id IS
            '操作人 id(来自 SecurityContext.UserPrincipal);NULL 时表示 SYSTEM';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_api_key_audit_log'::regclass AND a.attname = 'actor_role'
    ) THEN
        COMMENT ON COLUMN ai_api_key_audit_log.actor_role IS
            '操作人角色:ADMIN(管理后台改动) / SYSTEM(内部事件)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_api_key_audit_log'::regclass AND a.attname = 'metadata'
    ) THEN
        COMMENT ON COLUMN ai_api_key_audit_log.metadata IS
            'JSONB 上下文:{vendor, fingerprint_before, fingerprint_after, request_ip};改前改后指纹用以推断"是新装/轮换/清空"';
    END IF;
END $$;
