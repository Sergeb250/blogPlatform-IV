package com.blogPlatform.III.blog.security.jwt;

import java.time.Instant;

// id = the token's "jti" claim: the key under which it is tracked and, on logout, revoked
public record IssuedToken(String value, String id, Instant issuedAt, Instant expiresAt) {
}
