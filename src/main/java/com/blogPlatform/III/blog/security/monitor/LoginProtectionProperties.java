package com.blogPlatform.III.blog.security.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// app.security.login.*: how many failed logins (per username + IP) are allowed within which time
@ConfigurationProperties("app.security.login")
public record LoginProtectionProperties(int maxFailures, Duration window) {
}
