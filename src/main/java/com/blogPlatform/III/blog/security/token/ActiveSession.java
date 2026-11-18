package com.blogPlatform.III.blog.security.token;

import lombok.Getter;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

// One issued token and how it is used. Several requests with the same token can run at once, so the counter is
// an AtomicLong and the "last seen" fields are volatile (every thread sees the latest value).
public class ActiveSession {

    @Getter private final String tokenId;
    @Getter private final Long userId;
    @Getter private final String username;
    @Getter private final Instant issuedAt;
    @Getter private final Instant expiresAt;
    private final AtomicLong requests = new AtomicLong();
    @Getter private volatile Instant lastSeenAt;
    @Getter private volatile String lastIp;

    public ActiveSession(String tokenId, Long userId, String username, Instant issuedAt, Instant expiresAt, String ip) {
        this.tokenId = tokenId;
        this.userId = userId;
        this.username = username;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.lastSeenAt = issuedAt;
        this.lastIp = ip;
    }

    void recordRequest(String ip, Instant now) {
        requests.incrementAndGet();
        lastSeenAt = now;
        lastIp = ip;
    }

    public long requestCount() {
        return requests.get();
    }
}
