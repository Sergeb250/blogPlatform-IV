package com.blogPlatform.III.blog.dto.response;

import java.time.Instant;
import java.util.List;

public record SecurityReport(
        Instant generatedAt,
        long loginSuccesses,
        long loginFailures,
        long blockedLogins,
        long rejectedTokens,
        long unauthorizedRequests,
        long forbiddenRequests,
        int activeSessions,
        int revokedTokens,
        List<String> blockedNow,
        List<CountEntry> suspectedBruteForce,
        List<CountEntry> topEndpoints,
        List<CountEntry> topUsers
) {

    public record CountEntry(String name, long count) {
    }
}
