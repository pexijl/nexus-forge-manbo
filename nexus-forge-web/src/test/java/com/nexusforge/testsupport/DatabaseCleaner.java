package com.nexusforge.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 测试间隔离:清空 users 表(file 模块目前没有持久化表)。
 * RESTART IDENTITY 重置自增 id,避免不同测试间 id 漂移影响断言。
 */
@Component
public class DatabaseCleaner {

    @Autowired
    private JdbcTemplate jdbc;

    public void clean() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
    }
}