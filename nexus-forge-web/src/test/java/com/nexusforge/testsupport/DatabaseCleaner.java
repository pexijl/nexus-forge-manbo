package com.nexusforge.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 测试间隔离:清空 users 与 ai_* 系列表(file 模块目前没有持久化表)。
 * RESTART IDENTITY 重置自增 id,避免不同测试间 id 漂移影响断言。
 * 注意:ai_conversations.user_id 是无 FK 的 @Column,TRUNCATE users CASCADE 不会带走它;
 * 同样 ai_messages.conversation_id 也无 FK,需显式列出来。
 */
@Component
public class DatabaseCleaner {

    @Autowired
    private JdbcTemplate jdbc;

    public void clean() {
        // 注意顺序:子表先于父表,虽然 RESTART IDENTITY CASCADE 也能级联,但显式列出更稳。
        jdbc.execute("TRUNCATE TABLE ai_message_usage, ai_messages, ai_conversations, users RESTART IDENTITY CASCADE");
    }
}