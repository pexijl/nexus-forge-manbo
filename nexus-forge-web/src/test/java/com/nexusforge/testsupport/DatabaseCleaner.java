package com.nexusforge.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 测试间隔离:清空 users / ai_* / file_metadata / account_lifecycle_log 表。
 * RESTART IDENTITY 重置自增 id,避免不同测试间 id 漂移影响断言。
 * 注意:ai_conversations.user_id 是无 FK 的 @Column,TRUNCATE users CASCADE 不会带走它;
 * 同样 ai_messages.conversation_id / file_metadata.owner_id / account_lifecycle_log.user_id
 * 也无 FK,需显式列出来。
 */
@Component
public class DatabaseCleaner {

    @Autowired
    private JdbcTemplate jdbc;

    public void clean() {
        // 注意顺序:子表先于父表,虽然 RESTART IDENTITY CASCADE 也能级联,但显式列出更稳。
        jdbc.execute("TRUNCATE TABLE " +
                "ai_message_usage, ai_messages, ai_conversations, " +
                "file_metadata, account_lifecycle_log, " +
                "operation_audit_log, " +
                "users RESTART IDENTITY CASCADE");
    }
}