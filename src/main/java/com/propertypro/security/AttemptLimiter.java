package com.propertypro.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window counters kept in memory, keyed by strings such as "login:ip:1.2.3.4".
 * Enough for one instance; with several instances each one counts on its own, so the
 * effective limit is multiplied by the instance count (move to Redis or the database then).
 */
@Component
public class AttemptLimiter {

    private static final int PRUNE_THRESHOLD = 10_000;
    /** Longest window any caller uses; entries older than this are dropped when pruning. */
    private static final Duration MAX_WINDOW = Duration.ofHours(24);

    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public AttemptLimiter() {
        this(Clock.systemUTC());
    }

    AttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * How long until {@code key} may try again, or empty when it has fewer than
     * {@code max} attempts inside {@code window}.
     */
    public Optional<Duration> blockedFor(String key, int max, Duration window) {
        Deque<Instant> q = attempts.get(key);
        if (q == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        synchronized (q) {
            dropOlderThan(q, now.minus(window));
            if (q.size() < max) {
                return Optional.empty();
            }
            // Blocked until enough of the oldest attempts leave the window
            Instant freedAt = q.stream().skip(q.size() - max).findFirst().orElseThrow().plus(window);
            return Optional.of(Duration.between(now, freedAt));
        }
    }

    public void record(String key) {
        if (attempts.size() > PRUNE_THRESHOLD) {
            prune();
        }
        Deque<Instant> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(clock.instant());
        }
    }

    /**
     * Records an attempt unless the key is already over the limit; returns how long it is
     * blocked for when it is. Use for actions limited by count (sign-ups), not by failures.
     */
    public Optional<Duration> tryAcquire(String key, int max, Duration window) {
        Optional<Duration> blocked = blockedFor(key, max, window);
        if (blocked.isEmpty()) {
            record(key);
        }
        return blocked;
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(MAX_WINDOW);
        attempts.forEach((key, q) -> {
            synchronized (q) {
                dropOlderThan(q, cutoff);
                if (q.isEmpty()) {
                    attempts.remove(key, q);
                }
            }
        });
    }

    private static void dropOlderThan(Deque<Instant> q, Instant cutoff) {
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.pollFirst();
        }
    }
}
