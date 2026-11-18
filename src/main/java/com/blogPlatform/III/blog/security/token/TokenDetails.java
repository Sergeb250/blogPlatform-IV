package com.blogPlatform.III.blog.security.token;

import java.time.Instant;

// attached to the logged-in user of a request (Authentication.getDetails()): which token was used, so logout
// knows what to revoke
public record TokenDetails(String tokenId, Instant expiresAt) {
}
