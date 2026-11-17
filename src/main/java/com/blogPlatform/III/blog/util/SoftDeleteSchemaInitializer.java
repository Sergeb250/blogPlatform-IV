package com.blogPlatform.III.blog.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// Unique keys that ignore soft-deleted rows.
// A soft-deleted row stays in its table, so a plain UNIQUE (username) would still count it: a deleted user's
// name, a deleted post's title or a deleted tag's name could never be used again.
// MySQL has no partial unique index ("... WHERE deleted_at IS NULL"), so:
//   1. a generated column not_deleted is 1 while deleted_at is NULL, and NULL once the row is deleted
//   2. every unique key ends with it, e.g. UNIQUE (username, not_deleted)
// A unique index never compares NULLs: live rows (all 1) must differ, deleted rows (NULL) never clash.
@Slf4j
@Component
// after Hibernate has created/updated the tables, and before the web server takes requests
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
    private final SchemaHelper schema;

    @Override
    public void afterPropertiesSet() {
        List<String> tables = UNIQUE_KEYS.stream().map(LiveUniqueKey::table).distinct().toList();

        tables.forEach(this::addLiveColumnIfMissing);
        // new keys first: the old (post_id, user_id) / (author_id, title) keys also serve a foreign key,
        // and MySQL only lets us drop them once another index starts with the same column
        UNIQUE_KEYS.forEach(this::createUniqueKeyIfMissing);
        tables.forEach(this::dropUniqueKeysThatCountDeletedRows);
    }

    private void addLiveColumnIfMissing(String table) {
        if (!schema.columnExists(table, LIVE_COLUMN)) {
            log.info("Adding generated column {}.{}", table, LIVE_COLUMN);
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + LIVE_COLUMN
                    + " TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) STORED");
        }
    }

    private void createUniqueKeyIfMissing(LiveUniqueKey key) {
        if (!schema.indexExists(key.table(), key.name())) {
            log.info("Creating unique key {} on {} ({}, {})", key.name(), key.table(), key.columns(), LIVE_COLUMN);
            jdbc.execute("CREATE UNIQUE INDEX " + key.name() + " ON " + key.table()
                    + " (" + key.columns() + ", " + LIVE_COLUMN + ")");
        }
    }

    // the old keys from before soft delete, e.g. UNIQUE (username): they would still count deleted rows
    private void dropUniqueKeysThatCountDeletedRows(String table) {
        List<String> oldKeys = jdbc.queryForList("""
                SELECT INDEX_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                  AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY'
                GROUP BY INDEX_NAME
                HAVING SUM(COLUMN_NAME = ?) = 0
                """, String.class, table, LIVE_COLUMN);
        for (String index : oldKeys) {
            log.info("Dropping unique key {} on {}: it would also count soft-deleted rows", index, table);
            jdbc.execute("ALTER TABLE " + table + " DROP INDEX `" + index + "`");
        }
    }
}
