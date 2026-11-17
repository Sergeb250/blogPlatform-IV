package com.blogPlatform.III.blog.util;

import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.repository.RoleRepository;
import com.blogPlatform.III.blog.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Sign-up only ever creates READERs, so the first ADMIN is created here at startup.
// Only while no live user has the ADMIN role, and only with a configured password: there is no default one.
@Slf4j
@Component
// after the seeder (1), which only fills an EMPTY users table, and the FULLTEXT index (2)
@Order(3)
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 8;

    private final AdminProperties admin;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(RoleName.ADMIN)) {
            return;
        }
        if (admin.password() == null || admin.password().length() < MIN_PASSWORD_LENGTH) {
            log.warn("There is no admin account. Set ADMIN_PASSWORD (at least {} characters) to create one at startup",
                    MIN_PASSWORD_LENGTH);
            return;
        }
        // never promote an existing account: whoever signed up with that name would become an admin
        if (userRepository.existsByUsername(admin.username()) || userRepository.existsByEmail(admin.email())) {
            log.error("No admin account created: username '{}' or email '{}' already belongs to another account",
                    admin.username(), admin.email());
            return;
        }

        User user = new User();
        user.setUsername(admin.username());
        user.setEmail(admin.email());
        user.setPasswordHash(passwordEncoder.encode(admin.password()));
        user.getRoles().add(roleRepository.findByName(RoleName.ADMIN).orElseThrow());
        userRepository.save(user);
        log.info("Created the admin account '{}'", admin.username());
    }
}
