package com.blogPlatform.III.blog.security.token;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Revoked tokens. A JWT can't be deleted (the server keeps no copy), so "log out" means: remember the token's
// id here, and JwtAuthenticationFilter refuses it from then on.
@Component
public class TokenBlacklist {

    // token id (jti) -> the token's own expiry. ConcurrentHashMap: many request threads read it at the same time
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public void revoke(String tokenId, Instant expiresAt) {
        revoked.put(tokenId, expiresAt);
    }

    // one hash lookup per request: O(1), however many tokens are revoked
    public boolean isRevoked(String tokenId) {
        return revoked.containsKey(tokenId);
    }

    public int size() {
        return revoked.size();
    }

    // an expired token is refused by its "exp" claim anyway, so its entry is no longer needed: the map stays small
    @Scheduled(fixedRate = 60_000)
    public void purgeExpired() {
        removeExpired(Instant.now());
    }

    public int removeExpired(Instant now) {
        int before = revoked.size();
        revoked.values().removeIf(expiresAt -> expiresAt.isBefore(now));
        return before - revoked.size();
    }
}
