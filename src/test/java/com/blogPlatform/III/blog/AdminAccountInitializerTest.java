package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.repository.RoleRepository;
import com.blogPlatform.III.blog.repository.UserRepository;
import com.blogPlatform.III.blog.util.AdminAccountInitializer;
import com.blogPlatform.III.blog.util.AdminProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminAccountInitializerTest {

    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void noAdminYet() {
        // rolled back after each test, like everything else here
        jdbc.update("DELETE ur FROM user_roles ur JOIN roles r ON r.role_id = ur.role_id WHERE r.name = 'ADMIN'");
    }

    @Test
    void createsTheFirstAdmin_withAHashedPassword() {
        initializer("boss", "secret123").run(null);

        User boss = userRepository.findByUsername("boss").orElseThrow();
        assertEquals(Set.of(RoleName.ADMIN), boss.roleNames());
        assertNotEquals("secret123", boss.getPasswordHash());
        assertTrue(passwordEncoder.matches("secret123", boss.getPasswordHash()));
    }

    @Test
    void withoutAPassword_noAdminIsCreated() {
        initializer("boss", "").run(null);
        initializer("boss", "short").run(null);

        assertTrue(userRepository.findByUsername("boss").isEmpty());
    }

    @Test
    void anExistingAccount_isNeverPromoted() {
        // someone signed up as "boss" before the admin was configured: they must not become an admin
        User squatter = new User();
        squatter.setUsername("boss");
        squatter.setEmail("squatter@test.com");
        squatter.setPasswordHash("hash");
        squatter.getRoles().addAll(roleRepository.findByNameIn(List.of(RoleName.READER)));
        userRepository.save(squatter);

        initializer("boss", "secret123").run(null);

        assertEquals(Set.of(RoleName.READER), userRepository.findByUsername("boss").orElseThrow().roleNames());
        assertFalse(userRepository.existsByRole(RoleName.ADMIN));
    }

    @Test
    void onceThereIsAnAdmin_nothingMoreIsCreated() {
        initializer("boss", "secret123").run(null);
        initializer("second", "secret123").run(null);

        assertTrue(userRepository.findByUsername("second").isEmpty());
    }

    private AdminAccountInitializer initializer(String username, String password) {
        AdminProperties properties = new AdminProperties(username, username + "@test.com", password);
        return new AdminAccountInitializer(properties, userRepository, roleRepository, passwordEncoder);
    }
}
