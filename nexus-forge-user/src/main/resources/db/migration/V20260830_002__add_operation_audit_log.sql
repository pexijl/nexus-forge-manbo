-- 操作审计表 —— 记录"谁在什么时间调了什么 HTTP 端点、IP / UA / 状态码 / 延迟"
--
-- 与已有 account_lifecycle_log 区别:
--   account_lifecycle_log  = 高粒度业务事件(BAN / DELETE / RESTORE) + 业务 metadata
--   operation_audit_log   = 低粒度 HTTP 请求 + 性能 + 安全审计(谁从哪 IP 调了啥)
-- 两个并存,各管各的;account_lifecycle 走专用 AuditLogger + 业务 metadata,
-- operation 走 AOP @Audited 注解自动记录 HTTP 调用
--
-- 设计要点:
-- 1. user_id 可空(anon / system 调用)
-- 2. action 是 @Audited.value() 字符串,如 "user.update" / "file.upload"
-- 3. resource / resource_id 是泛化资源维度(类似 file_metadata.bizType)
--    · 业务方法 @Audited(resource="user", resourceId="#userId") 自动从 SpEL 取
-- 4. method + path 是 HTTP 维度(GET /api/users/me 等)
-- 5. ip / user_agent 来自 HttpServletRequest,限长防滥用
-- 6. result + status_code + error_code 三态联合表达请求结果
-- 7. latency_ms 是性能数据(纳秒精度计算后转 ms)
-- 8. metadata JSONB 存 SpEL 表达式求值的入参(默认 recordArgs=false 不存)
-- 9. created_at 业务无需软删(审计行只追加;合规追溯需要物理持久)
-- 10. DDL 幂等 IF NOT EXISTS — 容器复用场景下不会因"已存在"挂

CREATE TABLE IF NOT EXISTS operation_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT,                                  -- 谁干的(NULL = anon / system)
    action          VARCHAR(64)  NOT NULL,                   -- @Audited.value() 字符串
    resource        VARCHAR(64),                             -- @Audited.resource() 资源类型
    resource_id     VARCHAR(64),                             -- 资源 ID(从 SpEL 算)
    method          VARCHAR(8)   NOT NULL,                   -- HTTP method GET / POST ...
    path            VARCHAR(255) NOT NULL,                   -- URL path
    ip              VARCHAR(45),                             -- 客户端 IP(IPv6 max 45 chars)
    user_agent      VARCHAR(255),                            -- User-Agent 截断
    result          VARCHAR(16)  NOT NULL,                   -- SUCCESS / FAILURE
    status_code     INT,                                      -- HTTP 状态码
    latency_ms      BIGINT      NOT NULL,                    -- 耗时 ms
    error_code      INT,                                      -- 业务 code(失败时)
    metadata        JSONB,                                    -- @Audited(recordArgs=true) 时存
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 业务主查询:某用户最近操作
CREATE INDEX IF NOT EXISTS idx_op_audit_log_user_created
    ON operation_audit_log(user_id, created_at DESC);

-- 资源维度:某资源的全部操作历史(订单状态变更、用户资料修改等)
CREATE INDEX IF NOT EXISTS idx_op_audit_log_resource
    ON operation_audit_log(resource, resource_id, created_at DESC);

-- 时间维度:最近 N 分钟审计(管理员后台 / 合规导出)
CREATE INDEX IF NOT EXISTS idx_op_audit_log_created
    ON operation_audit_log(created_at DESC);
