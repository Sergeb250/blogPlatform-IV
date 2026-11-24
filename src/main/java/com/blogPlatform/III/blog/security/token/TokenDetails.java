package com.blogPlatform.III.blog.security.token;

import java.time.Instant;

public record TokenDetails(String tokenId, Instant expiresAt) {
}
