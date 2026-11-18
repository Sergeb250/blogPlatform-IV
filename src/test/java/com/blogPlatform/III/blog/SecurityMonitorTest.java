package com.blogPlatform.III.blog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.blogPlatform.III.blog.dto.response.SecurityReport;
import com.blogPlatform.III.blog.dto.response.SecurityReport.CountEntry;
import com.blogPlatform.III.blog.security.monitor.LoginAttemptService;
import com.blogPlatform.III.blog.security.monitor.LoginProtectionProperties;
import com.blogPlatform.III.blog.security.monitor.SecurityMonitor;
import com.blogPlatform.III.blog.security.monitor.TopK;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenBlacklist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

// the monitor on its own, without Spring: fresh counters in every test
class SecurityMonitorTest {

    final LoginProtectionProperties protection = new LoginProtectionProperties(5, Duration.ofMinutes(15));
    final TokenBlacklist blacklist = new TokenBlacklist();
    final SecurityMonitor monitor = new SecurityMonitor(
            new LoginAttemptService(protection, Clock.systemUTC()), protection,
            new ActiveSessionRegistry(blacklist), blacklist, Clock.systemUTC());

    final Logger securityLog = (Logger) LoggerFactory.getLogger("SECURITY");
    final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        events.start();
        securityLog.addAppender(events);
    }

    @AfterEach
    void stopCapture() {
        securityLog.detachAppender(events);
    }

    @Test
    void topK_returnsTheBiggestCounts_biggestFirst() {
        Map<String, AtomicLong> counts = new LinkedHashMap<>();
        long[] values = {4, 9, 1, 7, 7, 2, 12};
        for (int i = 0; i < values.length; i++) {
            counts.put("n" + i, new AtomicLong(values[i]));
        }

        List<CountEntry> top = TopK.of(counts, 3);

        assertEquals(List.of(new CountEntry("n6", 12), new CountEntry("n1", 9), new CountEntry("n3", 7)), top);
        assertEquals(2, TopK.of(Map.of("a", new AtomicLong(1), "b", new AtomicLong(2)), 5).size(),
                "fewer than k: all of them");
    }

    @Test
    void endpoints_areCountedPerRoute_notPerRow() {
        monitor.recordRequest("anna", "GET", "/api/posts/7");
        monitor.recordRequest("anna", "GET", "/api/posts/9");
        monitor.recordRequest("ben", "POST", "/api/posts/7/comments");

        SecurityReport report = monitor.report();

        assertEquals(new CountEntry("GET /api/posts/{id}", 2), report.topEndpoints().get(0));
        assertTrue(report.topEndpoints().contains(new CountEntry("POST /api/posts/{id}/comments", 1)));
        assertEquals(new CountEntry("anna", 2), report.topUsers().get(0));
    }

    @Test
    void manyFailures_makeAUserAndAnIpSuspects() {
        for (int i = 0; i < 6; i++) {
            monitor.loginFailed("admin", "203.0.113.7");
        }
        monitor.loginFailed("anna", "10.0.0.1");

        SecurityReport report = monitor.report();

        assertEquals(7, report.loginFailures());
        assertEquals(List.of(new CountEntry("ip 203.0.113.7", 6), new CountEntry("user admin", 6)),
                report.suspectedBruteForce());
        assertEquals(new CountEntry("admin", 6), report.topFailedUsernames().get(0));
    }

    @Test
    void everyEvent_isOneKeyValueLine() {
        monitor.loginFailed("anna", "10.0.0.1");
        monitor.loginSucceeded("anna", "10.0.0.1", "PASSWORD");
        monitor.loginBlocked("anna", "10.0.0.1", Duration.ofMinutes(9));
        monitor.tokensRevoked(7L, 2, "ROLE_CHANGE");

        assertEquals(List.of(
                "LOGIN_FAILURE user=anna ip=10.0.0.1 failures_for_user=1",
                "LOGIN_SUCCESS user=anna ip=10.0.0.1 method=PASSWORD",
                "LOGIN_BLOCKED user=anna ip=10.0.0.1 retry_after_s=540",
                "TOKENS_REVOKED user_id=7 count=2 reason=ROLE_CHANGE"), lines());
    }

    @Test
    void aUsernameWithALineBreak_cannotForgeASecondLogLine() {
        monitor.loginFailed("evil\nLOGIN_SUCCESS user=admin", "10.0.0.1");

        String line = lines().get(0);
        assertFalse(line.contains("\n"));
        assertTrue(line.startsWith("LOGIN_FAILURE user=evil_LOGIN_SUCCESS user=admin ip=10.0.0.1"));
    }

    private List<String> lines() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
