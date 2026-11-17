package com.blogPlatform.III.blog.util;

import com.blogPlatform.III.blog.entity.RoleName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// Inserts the three roles, and brings a Phase III database (one "role" column on users) to Phase IV
// (roles + user_roles). ddl-auto=update only ADDS tables and columns: it never moves data, drops a column
// or removes NOT NULL, so those steps are done here.
@Slf4j
@Component
// after Hibernate has created roles / user_roles, and before the web server takes requests
@DependsOn("entityManagerFactory")
@RequiredArgsConstructor
public class RoleSchemaInitializer implements InitializingBean {

    private final JdbcTemplate jdbc;
    private final SchemaHelper schema;

    @Override
    public void afterPropertiesSet() {
        insertMissingRoles();
        moveOldRoleColumn();
        allowEmptyPasswordHash();
    }

    private void insertMissingRoles() {
        for (RoleName role : RoleName.values()) {
            int added = jdbc.update("""
                    INSERT INTO roles (name)
                    SELECT ? FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = ?)
                    """, role.name(), role.name());
            if (added > 0) {
                log.info("Added role {}", role);
            }
        }
    }

    private void moveOldRoleColumn() {
        if (!schema.columnExists("users", "role")) {
            return;
        }
        // CAST: both columns are MySQL ENUMs with different value lists, so compare them as plain text
        int moved = jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.user_id, r.role_id
                FROM users u JOIN roles r ON CAST(r.name AS CHAR) = CAST(u.role AS CHAR)
                WHERE NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.user_id AND ur.role_id = r.role_id)
                """);
        log.info("Copied {} users.role values into user_roles", moved);

        // the old column is NOT NULL without a default: every new INSERT would fail while it exists
        jdbc.execute("ALTER TABLE users DROP COLUMN role");
        log.info("Dropped the old column users.role");
    }

    // Phase III made password_hash NOT NULL; Google accounts have no password
    private void allowEmptyPasswordHash() {
        if (!schema.isNullable("users", "password_hash")) {
            jdbc.execute("ALTER TABLE users MODIFY password_hash VARCHAR(255) NULL");
            log.info("users.password_hash may now be NULL");
        }
    }
}
