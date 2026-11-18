package com.blogPlatform.III.blog.security.monitor;

import com.blogPlatform.III.blog.dto.response.SecurityReport;
import com.blogPlatform.III.blog.dto.response.SecurityReport.CountEntry;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenBlacklist;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

// Every security event goes through here: one key=value line in logs/security.log (logger "SECURITY", see
// logback-spring.xml), plus counters in memory for GET /api/admin/security/report.
@Component
@RequiredArgsConstructor
public class SecurityMonitor {

    private static final Logger log = LoggerFactory.getLogger("SECURITY");
    private static final int TOP = 5;
    // /api/posts/7/comments/12 -> /api/posts/{id}/comments/{id}: one counter per endpoint, not per row
    private static final Pattern NUMBER_IN_PATH = Pattern.compile("/\\d+");

    private final LoginAttemptService loginAttempts;
    private final LoginProtectionProperties loginProtection;
    private final ActiveSessionRegistry sessions;
    private final TokenBlacklist blacklist;
    private final Clock clock;

    private final AtomicLong loginSuccesses = new AtomicLong();
    private final AtomicLong loginFailures = new AtomicLong();
    private final AtomicLong blockedLogins = new AtomicLong();
    private final AtomicLong rejectedTokens = new AtomicLong();
    private final AtomicLong unauthorizedRequests = new AtomicLong();
    private final AtomicLong forbiddenRequests = new AtomicLong();

    // name -> count: which accounts and addresses fail most, which endpoints and users are the busiest
    private final Map<String, AtomicLong> failuresByUsername = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failuresByIp = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestsByEndpoint = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestsByUser = new ConcurrentHashMap<>();

    public void loginSucceeded(String username, String ip, String method) {
        loginSuccesses.incrementAndGet();
        log.info("LOGIN_SUCCESS user={} ip={} method={}", clean(username), ip, method);
    }

    public void loginFailed(String username, String ip) {
        loginFailures.incrementAndGet();
        long userFailures = increment(failuresByUsername, clean(username));
        increment(failuresByIp, ip);
        log.warn("LOGIN_FAILURE user={} ip={} failures_for_user={}", clean(username), ip, userFailures);
    }

    public void loginBlocked(String username, String ip, Duration retryAfter) {
        blockedLogins.incrementAndGet();
        log.warn("LOGIN_BLOCKED user={} ip={} retry_after_s={}", clean(username), ip, retryAfter.toSeconds());
    }

    public void googleLoginFailed(String ip, String reason) {
        loginFailures.incrementAndGet();
        increment(failuresByIp, ip);
        log.warn("GOOGLE_LOGIN_FAILURE ip={} reason=\"{}\"", ip, clean(reason));
    }

    public void tokenRejected(HttpServletRequest request, String reason) {
        rejectedTokens.incrementAndGet();
        log.warn("TOKEN_REJECTED ip={} method={} path={} reason=\"{}\"",
                request.getRemoteAddr(), request.getMethod(), clean(request.getRequestURI()), reason);
    }

    public void unauthorized(HttpServletRequest request) {
        unauthorizedRequests.incrementAndGet();
        log.warn("ACCESS_UNAUTHENTICATED ip={} method={} path={}",
                request.getRemoteAddr(), request.getMethod(), clean(request.getRequestURI()));
    }

    // reason: ROLE (role or ownership rule said no) or CSRF (form without a valid token)
    public void forbidden(HttpServletRequest request, String reason) {
        forbiddenRequests.incrementAndGet();
        log.warn("ACCESS_DENIED user={} ip={} method={} path={} reason={}", currentUsername(),
                request.getRemoteAddr(), request.getMethod(), clean(request.getRequestURI()), reason);
    }

    public void logout(String username) {
        log.info("LOGOUT user={}", clean(username));
    }

    public void tokensRevoked(Long userId, int count, String reason) {
        log.info("TOKENS_REVOKED user_id={} count={} reason={}", userId, count, reason);
    }

    // every API request that got past the token check: who called which endpoint
    public void recordRequest(String username, String method, String uri) {
        increment(requestsByEndpoint, method + " " + NUMBER_IN_PATH.matcher(uri).replaceAll("/{id}"));
        increment(requestsByUser, username);
    }

    public SecurityReport report() {
        return new SecurityReport(
                clock.instant(),
                loginSuccesses.get(),
                loginFailures.get(),
                blockedLogins.get(),
                rejectedTokens.get(),
                unauthorizedRequests.get(),
                forbiddenRequests.get(),
                sessions.size(),
                blacklist.size(),
                loginAttempts.blockedNow(),
                suspects(),
                TopK.of(failuresByUsername, TOP),
                TopK.of(failuresByIp, TOP),
                TopK.of(requestsByEndpoint, TOP),
                TopK.of(requestsByUser, TOP));
    }

    // every username or IP with at least as many failed logins as it takes to get blocked
    private List<CountEntry> suspects() {
        long threshold = loginProtection.maxFailures();
        List<CountEntry> suspects = new ArrayList<>();
        failuresByUsername.forEach((name, count) -> {
            if (count.get() >= threshold) {
                suspects.add(new CountEntry("user " + name, count.get()));
            }
        });
        failuresByIp.forEach((ip, count) -> {
            if (count.get() >= threshold) {
                suspects.add(new CountEntry("ip " + ip, count.get()));
            }
        });
        suspects.sort(Comparator.comparingLong(CountEntry::count).reversed().thenComparing(CountEntry::name));
        return suspects;
    }

    public static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthUser user
                ? user.username()
                : "anonymous";
    }

    private static long increment(Map<String, AtomicLong> counts, String key) {
        return counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    // Log injection: a username like "x\nLOGIN_SUCCESS user=admin" would write a fake second line. Line breaks and
    // tabs become "_", and very long values are cut, so one event always stays one line.
    static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String oneLine = value.replaceAll("[\\r\\n\\t]", "_");
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "..." : oneLine;
    }
}
