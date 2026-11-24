package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.jwt.IssuedToken;
import com.blogPlatform.III.blog.security.token.ActiveSession;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenBlacklist;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TokenStoreTest {

    static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    // the blacklist reads time from this clock, so a test moves time instead of waiting
    final LoginAttemptServiceTest.MovableClock clock = new LoginAttemptServiceTest.MovableClock(NOW);
    final TokenBlacklist blacklist = new TokenBlacklist(clock);
    final ActiveSessionRegistry registry = new ActiveSessionRegistry(blacklist);

    final AuthUser anna = new AuthUser(1L, "anna", null, Set.of(RoleName.READER));
    final AuthUser ben = new AuthUser(2L, "ben", null, Set.of(RoleName.AUTHOR));

    @Test
    void blacklist_remembersRevokedIds() {
        blacklist.revoke("token-1", NOW.plus(Duration.ofHours(1)));

        assertTrue(blacklist.isRevoked("token-1"));
        assertFalse(blacklist.isRevoked("token-2"));
    }

    @Test
    void blacklist_dropsAnEntryExactlyWhenItsTokenExpires() {
        blacklist.revoke("short", NOW.plus(Duration.ofMinutes(10)));
        blacklist.revoke("long", NOW.plus(Duration.ofMinutes(30)));

        clock.advance(Duration.ofMinutes(9));
        assertTrue(blacklist.isRevoked("short"), "still inside its lifetime");

        clock.advance(Duration.ofMinutes(2));   // 11 minutes in: "short" has expired, "long" has not
        assertFalse(blacklist.isRevoked("short"));
        assertTrue(blacklist.isRevoked("long"));
        assertEquals(1, blacklist.size());

        clock.advance(Duration.ofMinutes(20));  // 31 minutes in
        assertFalse(blacklist.isRevoked("long"));
        assertEquals(0, blacklist.size());
    }

    @Test
    void blacklist_doesNotKeepATokenThatHasExpiredAlready() {
        blacklist.revoke("old", NOW.minus(Duration.ofMinutes(1)));
        blacklist.revoke("fresh", NOW.plus(Duration.ofMinutes(30)));

        assertFalse(blacklist.isRevoked("old"), "its own expiry refuses it anyway, no need to remember it");
        assertTrue(blacklist.isRevoked("fresh"));
        assertEquals(1, blacklist.size());
    }

    @Test
    void blacklist_lookupsDoNotProlongAnEntry() {
        blacklist.revoke("token", NOW.plus(Duration.ofMinutes(10)));

        for (int minute = 0; minute < 9; minute++) {
            clock.advance(Duration.ofMinutes(1));
            assertTrue(blacklist.isRevoked("token"));   // asked every minute
        }
        clock.advance(Duration.ofMinutes(2));

        assertFalse(blacklist.isRevoked("token"), "asking again and again did not keep it past the token's expiry");
    }

    @Test
    void registry_tracksEveryIssuedToken() {
        registry.register(token("a1"), anna, "10.0.0.1");
        registry.register(token("a2"), anna, "10.0.0.2");
        registry.register(token("b1"), ben, "10.0.0.3");

        assertEquals(3, registry.size());
        assertEquals(List.of("a1", "a2", "b1"),
                registry.sessions().stream().map(ActiveSession::getTokenId).sorted().toList());
    }

    @Test
    void logout_revokesOneToken_theOthersStay() {
        registry.register(token("a1"), anna, "10.0.0.1");
        registry.register(token("a2"), anna, "10.0.0.2");

        registry.revoke("a1", NOW.plus(Duration.ofHours(1)));

        assertTrue(blacklist.isRevoked("a1"));
        assertFalse(blacklist.isRevoked("a2"));
        assertEquals(1, registry.size());
    }

    @Test
    void revokeAll_hitsEveryTokenOfThatUserOnly() {
        registry.register(token("a1"), anna, "10.0.0.1");
        registry.register(token("a2"), anna, "10.0.0.2");
        registry.register(token("b1"), ben, "10.0.0.3");

        assertEquals(2, registry.revokeAll(anna.id(), "ADMIN_FORCED"));

        assertTrue(blacklist.isRevoked("a1"));
        assertTrue(blacklist.isRevoked("a2"));
        assertFalse(blacklist.isRevoked("b1"));
        assertEquals(0, registry.revokeAll(anna.id(), "ADMIN_FORCED"), "nothing left to revoke");
    }

    @Test
    void expiredSessions_leaveBothMaps() {
        registry.register(new IssuedToken("jwt", "old", NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1))),
                anna, "10.0.0.1");
        registry.register(token("fresh"), anna, "10.0.0.1");

        registry.removeExpired(NOW);

        assertEquals(1, registry.size());
        // "old" is gone from the user index too: revokeAll only finds "fresh"
        assertEquals(1, registry.revokeAll(anna.id(), "ADMIN_FORCED"));
    }

    private static IssuedToken token(String id) {
        return new IssuedToken("jwt-" + id, id, NOW, NOW.plus(Duration.ofHours(1)));
    }
}
