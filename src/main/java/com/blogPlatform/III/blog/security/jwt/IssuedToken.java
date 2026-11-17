package com.blogPlatform.III.blog.security.jwt;

import java.time.Instant;

public record IssuedToken(String value, Instant issuedAt, Instant expiresAt) {
}
