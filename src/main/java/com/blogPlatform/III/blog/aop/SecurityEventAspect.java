package com.blogPlatform.III.blog.aop;

import com.blogPlatform.III.blog.dto.request.LoginRequest;
import com.blogPlatform.III.blog.dto.response.SecurityReport;
import com.blogPlatform.III.blog.dto.response.SecurityReport.CountEntry;
import com.blogPlatform.III.blog.dto.response.TokenResponse;
import com.blogPlatform.III.blog.exception.TooManyLoginAttemptsException;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.monitor.LoginAttemptService;
import com.blogPlatform.III.blog.security.monitor.LoginProtectionProperties;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenBlacklist;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// All security monitoring in one class. Each advice runs around the method where an event happens (so the login,
// token and access code has no logging calls), writes one key=value line to a log file and counts the event for
// the admin report (GET /api/admin/security/report). The files and their retention are set in logback-spring.xml.
@Aspect
@Component
@RequiredArgsConstructor
public class SecurityEventAspect {

    private static final Logger log = LoggerFactory.getLogger("SECURITY");
    private static final Logger accessLog = LoggerFactory.getLogger("ACCESS");

    private static final String AUTH_SERVICE = "com.blogPlatform.III.blog.service.AuthService";
    private static final String ERROR_HANDLER = "com.blogPlatform.III.blog.security.SecurityErrorHandler";
    private static final int TOP = 5;
    private static final Comparator<CountEntry> BIGGEST_FIRST =
            Comparator.comparingLong(CountEntry::count).reversed().thenComparing(CountEntry::name);
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

    private final Map<String, AtomicLong> failuresByUsername = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failuresByIp = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestsByEndpoint = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestsByUser = new ConcurrentHashMap<>();

    @AfterReturning(pointcut = "execution(* " + AUTH_SERVICE + ".login(..)) && args(request, ip)",
            returning = "token", argNames = "request,ip,token")
    public void loginSucceeded(LoginRequest request, String ip, TokenResponse token) {
        loggedIn(token.username(), ip, "PASSWORD");
    }

    @AfterThrowing(pointcut = "execution(* " + AUTH_SERVICE + ".login(..)) && args(request, ip)",
            throwing = "error", argNames = "request,ip,error")
    public void loginFailed(LoginRequest request, String ip, BadCredentialsException error) {
        String username = clean(request.username());
        loginFailures.incrementAndGet();
        long userFailures = increment(failuresByUsername, username);
        increment(failuresByIp, ip);
        log.warn("LOGIN_FAILURE user={} ip={} failures_for_user={}", username, ip, userFailures);
    }

    @AfterThrowing(pointcut = "execution(* " + AUTH_SERVICE + ".login(..)) && args(request, ip)",
            throwing = "blocked", argNames = "request,ip,blocked")
    public void loginBlocked(LoginRequest request, String ip, TooManyLoginAttemptsException blocked) {
        blockedLogins.incrementAndGet();
        log.warn("LOGIN_BLOCKED user={} ip={} retry_after_s={}", clean(request.username()), ip,
                blocked.getRetryAfter().toSeconds());
    }

    @AfterReturning(pointcut = "execution(* " + AUTH_SERVICE + ".loginWithGoogle(..)) && args(user, ip)",
            argNames = "user,ip")
    public void googleLoginSucceeded(AuthUser user, String ip) {
        loggedIn(user.username(), ip, "GOOGLE");
    }

    @Before(value = "execution(* " + ERROR_HANDLER + ".rejectGoogleLogin(..)) && args(request, *, reason)",
            argNames = "request,reason")
    public void googleLoginFailed(HttpServletRequest request, String reason) {
        loginFailures.incrementAndGet();
        increment(failuresByIp, request.getRemoteAddr());
        log.warn("GOOGLE_LOGIN_FAILURE ip={} reason=\"{}\"", request.getRemoteAddr(), clean(reason));
    }

    @AfterReturning(pointcut = "execution(* " + AUTH_SERVICE + ".logout(..)) && args(*, username)",
            argNames = "username")
    public void logout(String username) {
        log.info("LOGOUT user={}", clean(username));
    }

    @Before(value = "execution(* " + ERROR_HANDLER + ".rejectToken(..)) && args(request, *, reason)",
            argNames = "request,reason")
    public void tokenRejected(HttpServletRequest request, String reason) {
        rejectedTokens.incrementAndGet();
        log.warn("TOKEN_REJECTED ip={} method={} path={} reason=\"{}\"",
                request.getRemoteAddr(), request.getMethod(), clean(request.getRequestURI()), reason);
    }

    @AfterReturning(pointcut = "execution(* com.blogPlatform.III.blog.security.token.ActiveSessionRegistry.revokeAll(..))"
            + " && args(userId, reason)", returning = "count", argNames = "userId,reason,count")
    public void tokensRevoked(Long userId, String reason, int count) {
        log.info("TOKENS_REVOKED user_id={} count={} reason={}", userId, count, reason);
    }

    @Before(value = "execution(* " + ERROR_HANDLER + ".commence(..)) && args(request, ..)", argNames = "request")
    public void unauthenticated(HttpServletRequest request) {
        unauthorizedRequests.incrementAndGet();
        log.warn("ACCESS_UNAUTHENTICATED ip={} method={} path={}",
                request.getRemoteAddr(), request.getMethod(), clean(request.getRequestURI()));
    }

    @Before(value = "(execution(* " + ERROR_HANDLER + ".handle(..))"
            + " || execution(* com.blogPlatform.III.blog.exception.GlobalExceptionHandler.handleAccessDenied(..)))"
            + " && args(request, ..)", argNames = "request")
    public void accessDenied(HttpServletRequest request) {
        denied(request, "ROLE");
    }

    @Before(value = "execution(* " + ERROR_HANDLER + ".rejectCsrf(..)) && args(request, ..)", argNames = "request")
    public void csrfRejected(HttpServletRequest request) {
        denied(request, "CSRF");
    }

    // called by AccessMonitorFilter for every /api/ request: one line in logs/access.log, and a count per endpoint
    // (/api/posts/7 and /api/posts/9 count as /api/posts/{id}) and per user
    public void recordRequest(String username, String ip, String method, String uri) {
        String path = clean(uri);
        increment(requestsByEndpoint, method + " " + NUMBER_IN_PATH.matcher(path).replaceAll("/{id}"));
        increment(requestsByUser, username);
        accessLog.info("API_REQUEST user={} ip={} method={} path={}", clean(username), ip, method, path);
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
                top(requestsByEndpoint),
                top(requestsByUser));
    }

    public static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthUser user
                ? user.username()
                : "anonymous";
    }

    private void loggedIn(String username, String ip, String method) {
        loginSuccesses.incrementAndGet();
        log.info("LOGIN_SUCCESS user={} ip={} method={}", clean(username), ip, method);
    }

    // reason: ROLE (a role or ownership rule said no) or CSRF (a form without a valid token)
    private void denied(HttpServletRequest request, String reason) {
        forbiddenRequests.incrementAndGet();
        log.warn("ACCESS_DENIED user={} ip={} method={} path={} reason={}", currentUsername(),
                request.getRemoteAddr(), request.getMethod(), clean(request.getRequestURI()), reason);
    }

    // usernames and IPs with at least maxFailures failed logins: an attack on one account, or from one place
    private List<CountEntry> suspects() {
        return Stream.concat(entries(failuresByUsername, "user "), entries(failuresByIp, "ip "))
                .filter(entry -> entry.count() >= loginProtection.maxFailures())
                .sorted(BIGGEST_FIRST)
                .toList();
    }

    private static List<CountEntry> top(Map<String, AtomicLong> counts) {
        return entries(counts, "").sorted(BIGGEST_FIRST).limit(TOP).toList();
    }

    private static Stream<CountEntry> entries(Map<String, AtomicLong> counts, String prefix) {
        return counts.entrySet().stream().map(entry -> new CountEntry(prefix + entry.getKey(), entry.getValue().get()));
    }

    private static long increment(Map<String, AtomicLong> counts, String key) {
        return counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    private static String clean(String value) {
        if (value == null) {
            return "-";
        }
        String oneLine = value.replaceAll("[\\r\\n\\t]", "_");
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "..." : oneLine;
    }
}
