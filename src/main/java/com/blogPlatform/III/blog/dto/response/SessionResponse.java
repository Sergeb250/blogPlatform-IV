package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.security.token.ActiveSession;

import java.time.Instant;

public record SessionResponse(String tokenId, Long userId, String username, Instant issuedAt, Instant expiresAt,
                              Instant lastSeenAt, String lastIp, long requests) {

    public static SessionResponse from(ActiveSession session) {
        return new SessionResponse(session.getTokenId(), session.getUserId(), session.getUsername(),
                session.getIssuedAt(), session.getExpiresAt(), session.getLastSeenAt(), session.getLastIp(),
                session.requestCount());
    }
}
