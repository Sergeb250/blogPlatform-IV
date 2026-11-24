package com.blogPlatform.III.blog.security.jwt;

import java.time.Instant;

public record IssuedToken(String value, String id, Instant issuedAt, Instant expiresAt) {
}
