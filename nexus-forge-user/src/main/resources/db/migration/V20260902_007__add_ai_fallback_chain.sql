-- V20260902_007__add_ai_fallback_chain.sql
-- Phase 7 — fallback-chain 策略 DB 化:把 ChatModelRouter 降级链从 yaml
-- (spring.ai.fallback-chain)持久化到 ai_fallback_chain 表,管理员可在线
-- 热改降级顺序,无需重启。
--
-- 设计:
--   - 单行表(id 永远 = 1,CHECK 约束),跟 ai_global_default 风格一致
--   - vendors 列存 JSONB 数组,元素是 vendor 名字符串(对应 ai_vendor_config.vendor)
--   - 空数组 / DB 无行 → FallbackChainService 走 yaml 兜底,无 yaml 则空降级链
--   - 启动期不 seed(跟 ai_vendor_config 行为不同):fallback chain 是"运营控制面",
--     不是"出厂镜像";DB 没配就用 yaml,运营第一次 PUT 才入 DB,生产部署不会
--     "启动期覆盖运营决策"
--
-- 跟 ai_vendor_config 的关系:
--   - ai_vendor_config 持每个 vendor 的 enabled / base_url / api_key
--   - ai_fallback_chain 只持"哪些 vendor 排成降级链"
--   - 链中 vendor 是否启用由 ai_vendor_config.enabled 决定(同 yaml 行为,跳过禁用项)
--
-- 写时校验(应用层 FallbackChainService.replace):
--   - 每个 vendor 名必须存在于 spring.ai.providers.*(yaml 视角)— 不在就 400
--   - 重复 vendor 在 replace 时被去重,跟 ChatModelRouter 的运行时去重逻辑对齐
--
-- 跟 ChatModelRouter.resolveWithFallback 的契约不变:返回 chain 仍是
-- 首选 + 去重/跳过无效后的有序 Resolved 列表,事件 FallbackChainChangedEvent
-- 触发 Caffeine cache 失效(改完下次 call 即生效)。

CREATE TABLE IF NOT EXISTS ai_fallback_chain (
                                       id         INT          PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                                       vendors    JSONB        NOT NULL DEFAULT '[]'::jsonb,
                                       created_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                       updated_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 不 INSERT 种子:见上文"启动期不 seed"说明。空 vendors 数组跟"DB 无行"语义一致,
-- FallbackChainService.findEffective()统一走 yaml 兜底。

-- 完整性:JSONB 必须是数组(防御性,JPA 序列化的 list 写出来是数组,但
-- 有人用 psql 直接改时可能塞标量)。CHECK 失败由 GlobalExceptionHandler
-- 转 422/data-integrity 错误,跟其它 CHECK 约束一致。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ai_fallback_chain_vendor_must_be_array'
    ) THEN
        ALTER TABLE ai_fallback_chain
            ADD CONSTRAINT ai_fallback_chain_vendor_must_be_array
            CHECK (jsonb_typeof(vendors) = 'array');
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_description WHERE objoid = 'ai_fallback_chain'::regclass AND objsubid = 0) THEN
        COMMENT ON TABLE ai_fallback_chain IS
            'AI 降级链(全局唯一有序 vendor 列表),Phase 7 起 admin 可改;DB 有 → 用 DB,DB 无 → 走 yaml 兜底;ChatModelRouter.resolveWithFallback 消费';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_fallback_chain'::regclass AND a.attname = 'id'
    ) THEN
        COMMENT ON COLUMN ai_fallback_chain.id IS '永远 = 1,单行表';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_fallback_chain'::regclass AND a.attname = 'vendors'
    ) THEN
        COMMENT ON COLUMN ai_fallback_chain.vendors IS
            '降级链 vendor 列表(JSONB 数组,小写 vendor 名);Phase 7 起 admin 可改;DB 有 → 用 DB,DB 无/空 → 走 yaml 兜底,无 yaml 则空降级链';
    END IF;
END $$;
