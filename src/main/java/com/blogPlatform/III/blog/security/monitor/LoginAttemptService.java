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

// Brute-force protection with a sliding window. For every "username|ip" we keep the times of the recent failed
// logins, oldest first. 5 failures inside 15 minutes -> blocked until the oldest of them is 15 minutes old.
@Component
@RequiredArgsConstructor
public class LoginAttemptService {

    private final LoginProtectionProperties properties;
    private final Clock clock;

    // a queue per key: new failures join at the back, failures older than the window leave at the front
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    // how long this username + IP must still wait; Duration.ZERO = may try now
    public Duration retryAfter(String username, String ip) {
        Deque<Instant> times = failures.get(key(username, ip));
        if (times == null) {
            return Duration.ZERO;
        }
        Instant now = clock.instant();
        dropOld(times, now);
        Instant oldest = times.peekFirst();
        if (oldest == null || times.size() < properties.maxFailures()) {
            return Duration.ZERO;
        }
        // free again as soon as the oldest failure slides out of the window
        return Duration.between(now, oldest.plus(properties.window()));
    }

    public void loginFailed(String username, String ip) {
        Instant now = clock.instant();
        Deque<Instant> times = failures.computeIfAbsent(key(username, ip), key -> new ConcurrentLinkedDeque<>());
        dropOld(times, now);
        times.addLast(now);
    }

    // a correct password ends the series: the next mistake starts counting from zero
    public void loginSucceeded(String username, String ip) {
        failures.remove(key(username, ip));
    }

    // "username|ip" keys that are blocked right now
    public List<String> blockedNow() {
        return failures.entrySet().stream()
                .filter(entry -> {
                    String[] parts = entry.getKey().split("\\|", 2);
                    return !retryAfter(parts[0], parts[1]).isZero();
                })
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    // keys whose failures are all older than the window are forgotten, so the map doesn't keep growing
    @Scheduled(fixedRate = 60_000)
    public void purgeOld() {
        Instant now = clock.instant();
        failures.values().forEach(times -> dropOld(times, now));
        failures.values().removeIf(Deque::isEmpty);
    }

    // lower case: "Anna" and "anna" are the same account in MySQL, so they must share one counter
    private static String key(String username, String ip) {
        return username.toLowerCase(Locale.ROOT) + "|" + ip;
    }

    // the queue is sorted by time, so only the front can be too old: O(number removed), no full scan
    private void dropOld(Deque<Instant> times, Instant now) {
        Instant limit = now.minus(properties.window());
        Instant oldest;
        while ((oldest = times.peekFirst()) != null && !oldest.isAfter(limit)) {
            times.pollFirst();
        }
    }
}
