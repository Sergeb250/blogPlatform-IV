package com.blogPlatform.III.blog.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// app.jwt.* from application.properties
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, Duration expiration, String issuer) {
}
