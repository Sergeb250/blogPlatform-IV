package com.blogPlatform.III.blog.util;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Read-only questions about the live MySQL schema, shared by the startup classes in this package.
// They ask first and change the schema only when needed, so a restart changes nothing.
@Component
@RequiredArgsConstructor
public class SchemaHelper {

    private final JdbcTemplate jdbc;

    public boolean columnExists(String table, String column) {
        return count("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, table, column) > 0;
    }

    public boolean isNullable(String table, String column) {
        return count("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ? AND IS_NULLABLE = 'YES'
                """, table, column) > 0;
    }

    public boolean indexExists(String table, String index) {
        return count("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                """, table, index) > 0;
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
