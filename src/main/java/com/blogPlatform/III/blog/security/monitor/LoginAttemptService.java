package com.blogPlatform.III.blog.security.monitor;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

// Sliding window per "username|ip": 5 failures within 15 minutes block it until the oldest one leaves the window
@Component
@RequiredArgsConstructor
public class LoginAttemptService {

    private final LoginProtectionProperties properties;
    private final Clock clock;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public Duration retryAfter(String username, String ip) {
        return retryAfter(key(username, ip));
    }

    public void loginFailed(String username, String ip) {
        Instant now = clock.instant();
        Deque<Instant> times = failures.computeIfAbsent(key(username, ip), key -> new ConcurrentLinkedDeque<>());
        dropOld(times, now);
        times.addLast(now);
    }

    public void loginSucceeded(String username, String ip) {
        failures.remove(key(username, ip));
    }

    public List<String> blockedNow() {
        return failures.keySet().stream()
                .filter(key -> !retryAfter(key).isZero())
                .sorted()
                .toList();
    }

    @Scheduled(fixedRate = 60_000)
    public void purgeOld() {
        Instant now = clock.instant();
        failures.values().forEach(times -> dropOld(times, now));
        failures.values().removeIf(Deque::isEmpty);
    }

    // zero while the window holds fewer than maxFailures; otherwise the time until the oldest failure leaves it
    private Duration retryAfter(String key) {
        Deque<Instant> times = failures.get(key);
        if (times == null) {
            return Duration.ZERO;
        }
        Instant now = clock.instant();
        dropOld(times, now);
        Instant oldest = times.peekFirst();
        if (oldest == null || times.size() < properties.maxFailures()) {
            return Duration.ZERO;
        }
        return Duration.between(now, oldest.plus(properties.window()));
    }

    // lower case: "Anna" and "anna" are the same account in MySQL, so they must share one counter
    private static String key(String username, String ip) {
        return username.toLowerCase(Locale.ROOT) + "|" + ip;
    }

    private void dropOld(Deque<Instant> times, Instant now) {
        Instant limit = now.minus(properties.window());
        Instant oldest;
        while ((oldest = times.peekFirst()) != null && !oldest.isAfter(limit)) {
            times.pollFirst();
        }
    }
}
