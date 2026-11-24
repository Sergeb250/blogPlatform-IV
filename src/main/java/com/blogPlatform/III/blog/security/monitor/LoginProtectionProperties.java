package com.blogPlatform.III.blog.security.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.security.login")
public record LoginProtectionProperties(int maxFailures, Duration window) {
}
