-- 账号生命周期审计表 —— 记录 ban / unban / 注销 / 恢复 / 真删等所有状态变更
--
-- 设计要点:
-- 1. 无 FK 到 users(id) —— 真删 users 时审计必须保留(合规追溯)
-- 2. action 枚举在应用层校验(Java enum),DB 层只校验 NOT NULL
-- 3. actor_id 可空 —— SYSTEM 类事件(grace-period 过期)没有具体操作人
-- 4. metadata JSONB 存上下文(IP / user-agent / 原 status / 原因码) ——
--    不强 schema,后续加字段不用 ALTER TABLE
-- 5. 不加 updated_at —— 审计只追加,从不更新
-- 6. CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS 幂等保护 —
--    集成测试容器可能重跑(虽然容器是 withReuse=true 跨 IT 共享),DDL 不能因
--    "已存在" 而失败(参考 V20260828_001 的设计)

CREATE TABLE IF NOT EXISTS account_lifecycle_log (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    action       VARCHAR(32) NOT NULL,
    actor_id     BIGINT,
    actor_role   VARCHAR(16) NOT NULL,
    reason       TEXT,
    metadata     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 按用户查(看自己 / 某人的历史)
CREATE INDEX IF NOT EXISTS idx_account_lifecycle_log_user_id ON account_lifecycle_log(user_id);

-- 按时间查(最近 N 条 / 时间窗审计)
CREATE INDEX IF NOT EXISTS idx_account_lifecycle_log_created_at ON account_lifecycle_log(created_at DESC);

-- 按 action 过滤(比如统计 ban 总数)
CREATE INDEX IF NOT EXISTS idx_account_lifecycle_log_action ON account_lifecycle_log(action);
