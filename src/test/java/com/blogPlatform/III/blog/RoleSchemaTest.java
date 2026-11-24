package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.dto.response.UserResponse;
import com.blogPlatform.III.blog.entity.AuthProvider;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class RoleSchemaTest {

    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager cacheManager;

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM users");
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void theThreeRoles_existOnce() {
        List<String> roles = jdbc.queryForList("SELECT name FROM roles ORDER BY name", String.class);

        assertEquals(List.of("ADMIN", "AUTHOR", "READER"), roles);
    }

    @Test
    void missingRoles_areInsertedAgainAtStartup_andARerunAddsNothing() {
        jdbc.update("DELETE FROM roles WHERE name = 'AUTHOR'");

        ResourceDatabasePopulator dataSql = new ResourceDatabasePopulator(new ClassPathResource("data.sql"));
        dataSql.execute(jdbc.getDataSource());
        dataSql.execute(jdbc.getDataSource());

        assertEquals(List.of("ADMIN", "AUTHOR", "READER"),
                jdbc.queryForList("SELECT name FROM roles ORDER BY name", String.class));
    }

    @Test
    void newUser_isAReader_throughUserRoles() {
        UserResponse anna = userService.register(new UserRequest("anna", "anna@test.com", "secret123"));

        assertEquals(Set.of(RoleName.READER), anna.roles());
        assertEquals(AuthProvider.LOCAL, anna.authProvider());
        assertEquals("READER", jdbc.queryForObject("""
                SELECT r.name FROM user_roles ur JOIN roles r ON r.role_id = ur.role_id WHERE ur.user_id = ?
                """, String.class, anna.id()));
    }

    @Test
    void passwordHash_mayBeEmpty_forGoogleAccounts() {
        jdbc.update("INSERT INTO users (username, email, password_hash, auth_provider, created_at) "
                + "VALUES ('google_user', 'g@gmail.com', NULL, 'GOOGLE', NOW())");

        assertNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE username = 'google_user'", String.class));
    }

    @Test
    void deletingAUserRow_removesItsRoleLinks() {
        UserResponse anna = userService.register(new UserRequest("anna", "anna@test.com", "secret123"));

        jdbc.update("DELETE FROM users WHERE user_id = ?", anna.id());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ?", Integer.class, anna.id()));
    }
}
