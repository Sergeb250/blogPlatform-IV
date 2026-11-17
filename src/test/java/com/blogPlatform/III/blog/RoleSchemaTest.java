package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.dto.response.UserResponse;
import com.blogPlatform.III.blog.entity.AuthProvider;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.service.UserService;
import com.blogPlatform.III.blog.util.RoleSchemaInitializer;
import com.blogPlatform.III.blog.util.SchemaHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// not @Transactional: ALTER TABLE commits by itself in MySQL, so the test cleans up by hand
@SpringBootTest
@ActiveProfiles("test")
class RoleSchemaTest {

    @Autowired UserService userService;
    @Autowired RoleSchemaInitializer roleSchemaInitializer;
    @Autowired SchemaHelper schema;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager cacheManager;

    @AfterEach
    void cleanDatabase() {
        if (roleColumnExists()) {
            jdbc.execute("ALTER TABLE users DROP COLUMN role");
        }
        jdbc.update("DELETE FROM users");
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void theThreeRoles_existOnce() {
        List<String> roles = jdbc.queryForList("SELECT name FROM roles ORDER BY name", String.class);

        assertEquals(List.of("ADMIN", "AUTHOR", "READER"), roles);
    }

    @Test
    void newUser_getsItsRoleThroughUserRoles() {
        UserResponse anna = userService.create(new UserRequest("anna", "anna@test.com", "secret123", null));
        UserResponse ben = userService.create(new UserRequest("ben", "ben@test.com", "secret123", RoleName.AUTHOR));

        assertEquals(Set.of(RoleName.READER), anna.roles());
        assertEquals(Set.of(RoleName.AUTHOR), ben.roles());
        assertEquals(AuthProvider.LOCAL, anna.authProvider());
        assertEquals("AUTHOR", jdbc.queryForObject("""
                SELECT r.name FROM user_roles ur JOIN roles r ON r.role_id = ur.role_id WHERE ur.user_id = ?
                """, String.class, ben.id()));
    }

    @Test
    void oldPhase3Column_isMovedIntoUserRoles_thenDropped() {
        // rebuild what a Phase III database looks like: the role lives in a column of users
        jdbc.execute("ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'READER'");
        jdbc.update("INSERT INTO users (username, email, password_hash, role, created_at) "
                + "VALUES ('old_author', 'old@test.com', 'hash', 'AUTHOR', NOW())");

        roleSchemaInitializer.afterPropertiesSet();

        assertFalse(roleColumnExists());
        assertEquals("AUTHOR", jdbc.queryForObject("""
                SELECT r.name FROM users u
                JOIN user_roles ur ON ur.user_id = u.user_id
                JOIN roles r ON r.role_id = ur.role_id
                WHERE u.username = 'old_author'
                """, String.class));

        // a second run finds nothing to do
        assertDoesNotThrow(() -> roleSchemaInitializer.afterPropertiesSet());
    }

    @Test
    void passwordHash_mayBeEmpty_forGoogleAccounts() {
        jdbc.update("INSERT INTO users (username, email, password_hash, auth_provider, created_at) "
                + "VALUES ('google_user', 'g@gmail.com', NULL, 'GOOGLE', NOW())");

        assertNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'google_user'", String.class));
    }

    @Test
    void deletingAUserRow_removesItsRoleLinks() {
        UserResponse anna = userService.create(new UserRequest("anna", "anna@test.com", "secret123", null));

        jdbc.update("DELETE FROM users WHERE user_id = ?", anna.id());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ?", Integer.class, anna.id()));
    }

    private boolean roleColumnExists() {
        return schema.columnExists("users", "role");
    }
}
