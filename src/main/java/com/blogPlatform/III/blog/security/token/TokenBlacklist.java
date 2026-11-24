package com.blogPlatform.III.blog.security.token;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

// Each entry expires together with its token: after that the token is refused by its own expiry anyway.
// No maximum size on purpose: evicting an entry early would make a revoked token valid again.
@Component
public class TokenBlacklist {

    private final Cache<String, Instant> revoked;

    public TokenBlacklist(Clock clock) {
        this.revoked = Caffeine.newBuilder()
                .ticker(() -> Duration.between(Instant.EPOCH, clock.instant()).toNanos())
                .expireAfter(Expiry.writing((String tokenId, Instant expiresAt) -> timeLeft(clock, expiresAt)))
                .build();
    }

    public void revoke(String tokenId, Instant expiresAt) {
        revoked.put(tokenId, expiresAt);
    }

    public boolean isRevoked(String tokenId) {
        return revoked.getIfPresent(tokenId) != null;
    }

    public int size() {
        revoked.cleanUp();
        return (int) revoked.estimatedSize();
    }

    private static Duration timeLeft(Clock clock, Instant expiresAt) {
        Duration left = Duration.between(clock.instant(), expiresAt);
        return left.isNegative() ? Duration.ZERO : left;
    }
}
