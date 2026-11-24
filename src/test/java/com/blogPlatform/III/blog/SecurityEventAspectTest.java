package com.blogPlatform.III.blog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.blogPlatform.III.blog.aop.SecurityEventAspect;
import com.blogPlatform.III.blog.dto.request.LoginRequest;
import com.blogPlatform.III.blog.dto.response.SecurityReport;
import com.blogPlatform.III.blog.dto.response.SecurityReport.CountEntry;
import com.blogPlatform.III.blog.dto.response.TokenResponse;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.exception.TooManyLoginAttemptsException;
import com.blogPlatform.III.blog.security.monitor.LoginAttemptService;
import com.blogPlatform.III.blog.security.monitor.LoginProtectionProperties;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenBlacklist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// the aspect as a plain object: its advice methods are called directly, the way the proxies call them
class SecurityEventAspectTest {

    static final BadCredentialsException WRONG_PASSWORD = new BadCredentialsException("Invalid username or password");

    final LoginProtectionProperties protection = new LoginProtectionProperties(5, Duration.ofMinutes(15));
    final TokenBlacklist blacklist = new TokenBlacklist(Clock.systemUTC());
    final SecurityEventAspect aspect = new SecurityEventAspect(
            new LoginAttemptService(protection, Clock.systemUTC()), protection,
            new ActiveSessionRegistry(blacklist), blacklist, Clock.systemUTC());

    final Logger securityLog = (Logger) LoggerFactory.getLogger("SECURITY");
    final Logger accessLog = (Logger) LoggerFactory.getLogger("ACCESS");
    final ListAppender<ILoggingEvent> events = new ListAppender<>();
    final ListAppender<ILoggingEvent> requests = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        events.start();
        securityLog.addAppender(events);
        requests.start();
        accessLog.addAppender(requests);
    }

    @AfterEach
    void stopCapture() {
        securityLog.detachAppender(events);
        accessLog.detachAppender(requests);
    }

    @Test
    void report_keepsTheFiveBiggestCounts_biggestFirst() {
        // user "u<n>" sends n requests, for n = 1 .. 7
        for (int n = 1; n <= 7; n++) {
            for (int i = 0; i < n; i++) {
                aspect.recordRequest("u" + n, "10.0.0." + n, "GET", "/api/posts");
            }
        }

        assertEquals(List.of(new CountEntry("u7", 7), new CountEntry("u6", 6), new CountEntry("u5", 5),
                        new CountEntry("u4", 4), new CountEntry("u3", 3)),
                aspect.report().topUsers());
    }

    @Test
    void report_listsEverythingWhenThereAreFewerThanFive_equalCountsByName() {
        aspect.recordRequest("ben", "10.0.0.1", "GET", "/api/posts");
        aspect.recordRequest("anna", "10.0.0.2", "GET", "/api/posts");

        assertEquals(List.of(new CountEntry("anna", 1), new CountEntry("ben", 1)),
                aspect.report().topUsers());
    }

    @Test
    void endpoints_areCountedPerRoute_notPerRow() {
        aspect.recordRequest("anna", "10.0.0.2", "GET", "/api/posts/7");
        aspect.recordRequest("anna", "10.0.0.2", "GET", "/api/posts/9");
        aspect.recordRequest("ben", "10.0.0.1", "POST", "/api/posts/7/comments");

        SecurityReport report = aspect.report();

        assertEquals(new CountEntry("GET /api/posts/{id}", 2), report.topEndpoints().get(0));
        assertTrue(report.topEndpoints().contains(new CountEntry("POST /api/posts/{id}/comments", 1)));
        assertEquals(new CountEntry("anna", 2), report.topUsers().get(0));
    }

    @Test
    void everyApiRequest_isOneLineInTheAccessLog() {
        aspect.recordRequest("anna", "10.0.0.4", "GET", "/api/posts/7");

        assertEquals(List.of("API_REQUEST user=anna ip=10.0.0.4 method=GET path=/api/posts/7"), lines(requests));
        assertEquals(List.of(), lines(events), "a normal request is not a security event");
    }

    @Test
    void manyFailures_makeAUserAndAnIpSuspects() {
        for (int i = 0; i < 6; i++) {
            aspect.loginFailed(login("admin"), "203.0.113.7", WRONG_PASSWORD);
        }
        aspect.loginFailed(login("anna"), "10.0.0.1", WRONG_PASSWORD);

        SecurityReport report = aspect.report();

        assertEquals(7, report.loginFailures());
        assertEquals(List.of(new CountEntry("ip 203.0.113.7", 6), new CountEntry("user admin", 6)),
                report.suspectedBruteForce());
    }

    @Test
    void everyEvent_isOneKeyValueLine() {
        aspect.loginFailed(login("anna"), "10.0.0.1", WRONG_PASSWORD);
        aspect.loginSucceeded(login("anna"), "10.0.0.1", token("anna"));
        aspect.loginBlocked(login("anna"), "10.0.0.1", new TooManyLoginAttemptsException(Duration.ofMinutes(9)));
        aspect.tokensRevoked(7L, "ROLE_CHANGE", 2);

        assertEquals(List.of(
                "LOGIN_FAILURE user=anna ip=10.0.0.1 failures_for_user=1",
                "LOGIN_SUCCESS user=anna ip=10.0.0.1 method=PASSWORD",
                "LOGIN_BLOCKED user=anna ip=10.0.0.1 retry_after_s=540",
                "TOKENS_REVOKED user_id=7 count=2 reason=ROLE_CHANGE"), lines(events));
    }

    @Test
    void aUsernameWithALineBreak_cannotForgeASecondLogLine() {
        aspect.loginFailed(login("evil\nLOGIN_SUCCESS user=admin"), "10.0.0.1", WRONG_PASSWORD);

        String line = lines(events).get(0);
        assertFalse(line.contains("\n"));
        assertTrue(line.startsWith("LOGIN_FAILURE user=evil_LOGIN_SUCCESS user=admin ip=10.0.0.1"));
    }

    private static LoginRequest login(String username) {
        return new LoginRequest(username, "secret123");
    }

    private static TokenResponse token(String username) {
        return new TokenResponse("token", "Bearer", 3600, Instant.now(), username, Set.of(RoleName.READER));
    }

    private static List<String> lines(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
