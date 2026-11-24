package com.blogPlatform.III.blog.security.token;

import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.jwt.IssuedToken;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Two maps, so each question is one lookup: session by token id (every request), token ids by user (revoke all)
@Component
@RequiredArgsConstructor
public class ActiveSessionRegistry {

    private final TokenBlacklist blacklist;

    private final Map<String, ActiveSession> sessionsByTokenId = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> tokenIdsByUser = new ConcurrentHashMap<>();

    public void register(IssuedToken token, AuthUser user, String ip) {
        add(new ActiveSession(token.id(), user.id(), user.username(), token.issuedAt(), token.expiresAt(), ip));
    }

    // a token issued before a restart is unknown here, so it is added the first time it is seen
    public void recordRequest(Claims claims, String ip) {
        ActiveSession session = sessionsByTokenId.get(claims.getId());
        if (session == null) {
            session = new ActiveSession(claims.getId(), claims.get("uid", Long.class), claims.getSubject(),
                    claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant(), ip);
            add(session);
        }
        session.recordRequest(ip, Instant.now());
    }

    public void revoke(String tokenId, Instant expiresAt) {
        blacklist.revoke(tokenId, expiresAt);
        ActiveSession session = sessionsByTokenId.remove(tokenId);
        if (session != null) {
            removeFromUser(session.getUserId(), tokenId);
        }
    }

    // the reason is only for the TOKENS_REVOKED log line (SecurityEventAspect)
    public int revokeAll(Long userId, String reason) {
        Set<String> tokenIds = tokenIdsByUser.remove(userId);
        if (tokenIds == null) {
            return 0;
        }
        for (String tokenId : tokenIds) {
            ActiveSession session = sessionsByTokenId.remove(tokenId);
            if (session != null) {
                blacklist.revoke(tokenId, session.getExpiresAt());
            }
        }
        return tokenIds.size();
    }

    public List<ActiveSession> sessions() {
        return sessionsByTokenId.values().stream()
                .sorted(Comparator.comparing(ActiveSession::getLastSeenAt).reversed())
                .toList();
    }

    public int size() {
        return sessionsByTokenId.size();
    }

    @Scheduled(fixedRate = 60_000)
    public void purgeExpired() {
        removeExpired(Instant.now());
    }

    public void removeExpired(Instant now) {
        sessionsByTokenId.values().removeIf(session -> {
            boolean expired = session.getExpiresAt().isBefore(now);
            if (expired) {
                removeFromUser(session.getUserId(), session.getTokenId());
            }
            return expired;
        });
    }

    private void add(ActiveSession session) {
        sessionsByTokenId.put(session.getTokenId(), session);
        tokenIdsByUser.computeIfAbsent(session.getUserId(), id -> ConcurrentHashMap.newKeySet()).add(session.getTokenId());
    }

    private void removeFromUser(Long userId, String tokenId) {
        // returning null removes the user's entry once their last token is gone
        tokenIdsByUser.computeIfPresent(userId, (id, tokenIds) -> {
            tokenIds.remove(tokenId);
            return tokenIds.isEmpty() ? null : tokenIds;
        });
    }
}
