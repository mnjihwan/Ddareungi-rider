package com.ddareungi.global.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Instance-local token buckets. Used for the station/status group, as the whole limiter on the local profile and as
 * the fail-open fallback when Redis is unavailable. Buckets are replaced atomically per key ({@code compute}); idle
 * buckets (already full again) are swept every {@code cleanupInterval} or when the map exceeds {@code maxEntries}.
 * Above {@code maxEntries} after a sweep, new clients are let through untracked rather than growing memory.
 */
public class LocalTokenBucketRateLimiter implements RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LocalTokenBucketRateLimiter.class);
    private static final long WARN_INTERVAL_MICROS = 60_000_000L;
    private static final long FULL_SWEEP_MIN_MICROS = 1_000_000L;

    private record Entry(TokenBucket bucket, RateLimitPolicy policy) {}

    private final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;
    private final long cleanupIntervalMicros;
    private final AtomicLong lastSweepMicros;
    private final AtomicLong lastFullWarnMicros = new AtomicLong(Long.MIN_VALUE);

    public LocalTokenBucketRateLimiter(Clock clock, int maxEntries, Duration cleanupInterval) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be >= 1");
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.cleanupIntervalMicros = cleanupInterval.toNanos() / 1_000;
        this.lastSweepMicros = new AtomicLong(nowMicros());
    }

    @Override
    public RateLimitDecision tryAcquire(String key, RateLimitPolicy policy) {
        long now = nowMicros();
        sweepIfDue(now);
        if (buckets.size() >= maxEntries && !buckets.containsKey(key)) {
            warnFull(now);
            return RateLimitDecision.ALLOW;
        }
        RateLimitDecision[] decision = new RateLimitDecision[1];
        buckets.compute(key, (ignored, entry) -> {
            TokenBucket bucket = entry == null || !entry.policy().equals(policy)
                    ? TokenBucket.full(policy, now)
                    : entry.bucket().refill(policy, now);
            if (bucket.hasToken()) {
                decision[0] = RateLimitDecision.ALLOW;
                return new Entry(bucket.take(), policy);
            }
            decision[0] = RateLimitDecision.reject(bucket.waitMicros(policy));
            return new Entry(bucket, policy);
        });
        return decision[0];
    }

    /** Number of tracked buckets (for tests and diagnostics). */
    int size() { return buckets.size(); }

    /** Removes buckets that would already be full; a concurrent update replaces the entry and is kept. */
    void sweep() {
        long now = nowMicros();
        lastSweepMicros.set(now);
        buckets.entrySet().removeIf(e -> e.getValue().bucket().idle(e.getValue().policy(), now));
    }

    private void sweepIfDue(long now) {
        long last = lastSweepMicros.get();
        long elapsed = now - last;
        // When full, sweep sooner than the interval but at most once a second: a sweep walks the whole map.
        boolean due = elapsed >= cleanupIntervalMicros || elapsed < 0
                || (buckets.size() >= maxEntries && elapsed >= FULL_SWEEP_MIN_MICROS);
        if (due && lastSweepMicros.compareAndSet(last, now)) {
            buckets.entrySet().removeIf(e -> e.getValue().bucket().idle(e.getValue().policy(), now));
        }
    }

    private void warnFull(long now) {
        long last = lastFullWarnMicros.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MICROS) && lastFullWarnMicros.compareAndSet(last, now)) {
            log.warn("Local rate limit table is full ({} entries); new clients are not limited until it drains",
                    maxEntries);
        }
    }

    private long nowMicros() {
        Instant now = clock.instant();
        return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000L), now.getNano() / 1_000);
    }
}
