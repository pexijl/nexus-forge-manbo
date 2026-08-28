-- V20260720_001__create_ai_tables.sql
-- P3:AI 对话上下文管理 — 对话表、消息表、用量表
--
-- 已用 CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS 兼容:
-- JPA ddl-auto=update 在切换到 Flyway 之前已建过这 3 张表,直接 CREATE 会报
-- "relation already exists"(SQL State 42P07)导致整条迁移回滚。

CREATE TABLE IF NOT EXISTS ai_conversations (
                                  id          BIGSERIAL    PRIMARY KEY,
                                  user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                  title       VARCHAR(255) NOT NULL DEFAULT '新对话',
                                  model       VARCHAR(64)  NOT NULL,
                                  pinned      BOOLEAN      NOT NULL DEFAULT FALSE,
                                  created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_conv_user_updated ON ai_conversations(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_conv_user_pinned  ON ai_conversations(user_id, pinned DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS ai_messages (
                             id               BIGSERIAL    PRIMARY KEY,
                             conversation_id  BIGINT       NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
                             role             VARCHAR(16)  NOT NULL,
                             content          TEXT         NOT NULL,
                             tool_calls       JSONB,
                             seq              INTEGER      NOT NULL,
                             created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_msg_conv_seq ON ai_messages(conversation_id, seq);

-- 同一对话内 seq 唯一。约束名相同 → 已存在时跳过。DO block 兼容 PostgreSQL 9.5+
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_ai_msg_conv_seq') THEN
        ALTER TABLE ai_messages ADD CONSTRAINT uk_ai_msg_conv_seq UNIQUE (conversation_id, seq);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS ai_message_usage (
                                  message_id         BIGINT   PRIMARY KEY REFERENCES ai_messages(id) ON DELETE CASCADE,
                                  prompt_tokens      INT      NOT NULL,
                                  completion_tokens  INT      NOT NULL,
                                  total_tokens       INT      NOT NULL,
                                  model              VARCHAR(64) NOT NULL
);