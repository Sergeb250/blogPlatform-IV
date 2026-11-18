package com.blogPlatform.III.blog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // a bean instead of Instant.now() everywhere: a test can hand in its own clock and move time without waiting
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
