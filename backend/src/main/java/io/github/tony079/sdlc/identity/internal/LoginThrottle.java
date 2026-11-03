package io.github.tony079.sdlc.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limit on failed logins, per account key and per client address (TDD 20.1, 22.2).
 *
 * <p>Known limit (decision register D-M1-02): counters are in memory, so they reset on restart and are not
 * shared between instances. Adequate for the single-instance Milestone 1 deployment only.
 */
@Component
class LoginThrottle {

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final int maxPerAccount;
    private final int maxPerClient;
    private final Duration window;
    private final Clock clock;

    @Autowired
    LoginThrottle(@Value("${sdlc.security.login-throttle.max-failures-per-account}") int maxPerAccount,
                  @Value("${sdlc.security.login-throttle.max-failures-per-client}") int maxPerClient,
                  @Value("${sdlc.security.login-throttle.window}") Duration window) {
        this(maxPerAccount, maxPerClient, window, Clock.systemUTC());
    }

    LoginThrottle(int maxPerAccount, int maxPerClient, Duration window, Clock clock) {
        this.maxPerAccount = maxPerAccount;
        this.maxPerClient = maxPerClient;
        this.window = window;
        this.clock = clock;
    }

    /** Time to wait if either key has reached its limit within the window. */
    Optional<Duration> blockedFor(String accountKey, String clientKey) {
        Instant now = clock.instant();
        Optional<Duration> account = blocked("a:" + accountKey, maxPerAccount, now);
        return account.isPresent() ? account : blocked("c:" + clientKey, maxPerClient, now);
    }

    void recordFailure(String accountKey, String clientKey) {
        Instant now = clock.instant();
        add("a:" + accountKey, now);
        add("c:" + clientKey, now);
    }

    void recordSuccess(String accountKey) {
        failures.remove("a:" + accountKey);
    }

    private Optional<Duration> blocked(String key, int max, Instant now) {
        Deque<Instant> times = failures.get(key);
        if (times == null) {
            return Optional.empty();
        }
        synchronized (times) {
            prune(times, now);
            if (times.size() < max) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(now, times.peekFirst().plus(window)));
        }
    }

    private void add(String key, Instant now) {
        Deque<Instant> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, now);
            times.addLast(now);
        }
    }

    private void prune(Deque<Instant> times, Instant now) {
        while (!times.isEmpty() && !times.peekFirst().plus(window).isAfter(now)) {
            times.removeFirst();
        }
    }
}
