package com.blogPlatform.III.blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    // a bean instead of a static helper: services get it injected, and tests or a later security config
    // use the very same encoder
    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt adds a random salt to every hash and is slow on purpose (cost 10 = 2^10 rounds),
        // so a stolen hash is expensive to crack
        return new BCryptPasswordEncoder();
    }
}
