-- V20260828_001__add_deleted_at.sql
-- 软删除基础设施:为所有继承 BaseEntity 的表加 deleted_at 字段
-- 配合 BaseEntity 的 @SQLDelete + @SQLRestriction 实现自动软删 + 查询自动过滤
--
-- 已用 IF NOT EXISTS 兼容:JPA ddl-auto=update 在切到 Flyway 之前可能已建该列
-- 三个继承 BaseEntity 的实体:users / ai_conversations / ai_messages
-- ai_message_usage / ai_global_default / user_ai_preference 不继承 BaseEntity,这次不动

ALTER TABLE users           ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;
ALTER TABLE ai_conversations ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;
ALTER TABLE ai_messages     ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;

-- 索引:支持"按时间范围扫描已软删"(管理端审计/恢复场景)
-- 部分索引更高效(绝大多数行 deleted_at IS NULL,只对非 NULL 建索引)
CREATE INDEX IF NOT EXISTS idx_users_deleted_at_partial
    ON users(deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ai_conv_deleted_at_partial
    ON ai_conversations(deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ai_msg_deleted_at_partial
    ON ai_messages(deleted_at) WHERE deleted_at IS NOT NULL;

-- COMMENT 幂等封装(用 DO block 检查,避免 42710 重复 COMMENT 报错)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'users'::regclass AND a.attname = 'deleted_at'
    ) THEN
        COMMENT ON COLUMN users.deleted_at IS '软删除时间;NULL=活,非 NULL=已软删(由 BaseEntity.@SQLDelete 自动写入)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_conversations'::regclass AND a.attname = 'deleted_at'
    ) THEN
        COMMENT ON COLUMN ai_conversations.deleted_at IS '软删除时间;NULL=活,非 NULL=已软删';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_description d
        JOIN pg_attribute a ON a.attrelid = d.objoid AND a.attnum = d.objsubid
        WHERE d.objoid = 'ai_messages'::regclass AND a.attname = 'deleted_at'
    ) THEN
        COMMENT ON COLUMN ai_messages.deleted_at IS '软删除时间;NULL=活,非 NULL=已软删';
    END IF;
END $$;
