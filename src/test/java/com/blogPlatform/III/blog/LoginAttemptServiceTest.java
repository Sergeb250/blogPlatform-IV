package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.security.monitor.LoginAttemptService;
import com.blogPlatform.III.blog.security.monitor.LoginProtectionProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptServiceTest {

    final MovableClock clock = new MovableClock(Instant.parse("2026-11-18T12:00:00Z"));
    final LoginAttemptService attempts =
            new LoginAttemptService(new LoginProtectionProperties(5, Duration.ofMinutes(15)), clock);

    @Test
    void fourFailures_stillAllowed_theFifthBlocks() {
        failTimes(4, Duration.ZERO);
        assertEquals(Duration.ZERO, attempts.retryAfter("anna", "10.0.0.1"));

        attempts.loginFailed("anna", "10.0.0.1");

        assertEquals(Duration.ofMinutes(15), attempts.retryAfter("anna", "10.0.0.1"));
    }

    @Test
    void theWindowSlides_oneFailureLeavesEveryTimeTheOldestGetsTooOld() {
        // 12:00, 12:01, 12:02, 12:03, 12:04
        failTimes(5, Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(-1));                       // back to 12:04
        assertEquals(Duration.ofMinutes(11), attempts.retryAfter("anna", "10.0.0.1"));

        clock.advance(Duration.ofMinutes(11));                      // 12:15: the 12:00 failure is out of the window
        assertEquals(Duration.ZERO, attempts.retryAfter("anna", "10.0.0.1"));

        attempts.loginFailed("anna", "10.0.0.1");                   // 5 inside the window again
        assertEquals(Duration.ofMinutes(1), attempts.retryAfter("anna", "10.0.0.1"));
    }

    @Test
    void aCorrectPassword_resetsTheCount() {
        failTimes(4, Duration.ZERO);

        attempts.loginSucceeded("anna", "10.0.0.1");
        attempts.loginFailed("anna", "10.0.0.1");

        assertEquals(Duration.ZERO, attempts.retryAfter("anna", "10.0.0.1"));
    }

    @Test
    void theCountBelongsToOneUsernameAndOneIp() {
        failTimes(5, Duration.ZERO);

        assertFalse(attempts.retryAfter("anna", "10.0.0.1").isZero());
        assertFalse(attempts.retryAfter("ANNA", "10.0.0.1").isZero(), "same account, other spelling");
        assertTrue(attempts.retryAfter("anna", "10.0.0.2").isZero(), "the real Anna on another network");
        assertTrue(attempts.retryAfter("ben", "10.0.0.1").isZero());
        assertEquals(List.of("anna|10.0.0.1"), attempts.blockedNow());
    }

    @Test
    void oldEntries_areForgotten() {
        failTimes(5, Duration.ZERO);
        clock.advance(Duration.ofMinutes(16));

        attempts.purgeOld();

        assertEquals(List.of(), attempts.blockedNow());
        assertEquals(Duration.ZERO, attempts.retryAfter("anna", "10.0.0.1"));
    }

    private void failTimes(int times, Duration between) {
        for (int i = 0; i < times; i++) {
            attempts.loginFailed("anna", "10.0.0.1");
            clock.advance(between);
        }
    }

    static class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
