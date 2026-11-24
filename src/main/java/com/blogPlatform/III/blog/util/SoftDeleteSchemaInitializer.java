package com.blogPlatform.III.blog.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// Unique keys that ignore soft-deleted rows. MySQL has no partial index, so each key ends with not_deleted,
// a generated column: 1 while live, NULL once deleted. NULLs never clash, so a deleted row frees its name.
@Slf4j
@Component
@DependsOn("entityManagerFactory")
@RequiredArgsConstructor
public class SoftDeleteSchemaInitializer implements InitializingBean {

    static final String LIVE_COLUMN = "not_deleted";

    record LiveUniqueKey(String table, String name, String columns) {
    }

    static final List<LiveUniqueKey> UNIQUE_KEYS = List.of(
            new LiveUniqueKey("users", "uk_users_username_live", "username"),
            new LiveUniqueKey("users", "uk_users_email_live", "email"),
            new LiveUniqueKey("posts", "uk_posts_author_title_live", "author_id, title"),
            new LiveUniqueKey("reviews", "uk_reviews_post_user_live", "post_id, user_id"),
            new LiveUniqueKey("tags", "uk_tags_name_live", "name"));

    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        UNIQUE_KEYS.stream().map(LiveUniqueKey::table).distinct().forEach(this::addLiveColumnIfMissing);
        UNIQUE_KEYS.forEach(this::createUniqueKeyIfMissing);
    }

    private void addLiveColumnIfMissing(String table) {
        if (!columnExists(table, LIVE_COLUMN)) {
            log.info("Adding generated column {}.{}", table, LIVE_COLUMN);
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + LIVE_COLUMN
                    + " TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) STORED");
        }
    }

    private void createUniqueKeyIfMissing(LiveUniqueKey key) {
        if (!indexExists(key.table(), key.name())) {
            log.info("Creating unique key {} on {} ({}, {})", key.name(), key.table(), key.columns(), LIVE_COLUMN);
            jdbc.execute("CREATE UNIQUE INDEX " + key.name() + " ON " + key.table()
                    + " (" + key.columns() + ", " + LIVE_COLUMN + ")");
        }
    }

    private boolean columnExists(String table, String column) {
        return count("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, table, column) > 0;
    }

    private boolean indexExists(String table, String index) {
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
