-- V20260720_001__create_ai_tables.sql
-- P3:AI 对话上下文管理 — 对话表、消息表、用量表

CREATE TABLE ai_conversations (
                                  id          BIGSERIAL    PRIMARY KEY,
                                  user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                  title       VARCHAR(255) NOT NULL DEFAULT '新对话',
                                  model       VARCHAR(64)  NOT NULL,
                                  pinned      BOOLEAN      NOT NULL DEFAULT FALSE,
                                  created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_conv_user_updated ON ai_conversations(user_id, updated_at DESC);
CREATE INDEX idx_ai_conv_user_pinned  ON ai_conversations(user_id, pinned DESC, updated_at DESC);

CREATE TABLE ai_messages (
                             id               BIGSERIAL    PRIMARY KEY,
                             conversation_id  BIGINT       NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
                             role             VARCHAR(16)  NOT NULL,
                             content          TEXT         NOT NULL,
                             tool_calls       JSONB,
                             seq              INTEGER      NOT NULL,
                             created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_msg_conv_seq ON ai_messages(conversation_id, seq);

-- 同一对话内 seq 唯一
ALTER TABLE ai_messages ADD CONSTRAINT uk_ai_msg_conv_seq UNIQUE (conversation_id, seq);

CREATE TABLE ai_message_usage (
                                  message_id         BIGINT   PRIMARY KEY REFERENCES ai_messages(id) ON DELETE CASCADE,
                                  prompt_tokens      INT      NOT NULL,
                                  completion_tokens  INT      NOT NULL,
                                  total_tokens       INT      NOT NULL,
                                  model              VARCHAR(64) NOT NULL
);