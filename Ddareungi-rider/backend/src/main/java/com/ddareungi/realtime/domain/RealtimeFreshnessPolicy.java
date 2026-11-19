package com.ddareungi.realtime.domain;

import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Single source of the FRESH/STALE/UNAVAILABLE boundaries (spec v5 11.0, X3) for every API. Callers take
 * {@link #now()} once per request and classify every station of that request against the same instant.
 *
 * <p>Rules for {@link #classify(Instant, Instant)}:
 * <ul>
 *   <li>{@code age = max(0, floor(now - updatedAt))} in whole seconds.</li>
 *   <li>{@code updatedAt} more than {@link #MAX_CLOCK_SKEW} (30 s) after {@code now} → UNAVAILABLE (age 0).</li>
 *   <li>{@code age <= freshSeconds} → FRESH; {@code age <= maxStaleSeconds} → STALE; otherwise UNAVAILABLE.</li>
 *   <li>{@code updatedAt == null} (no valid collection) → UNAVAILABLE with a null age.</li>
 * </ul>
 */
public final class RealtimeFreshnessPolicy {
    public static final long DEFAULT_FRESH_SECONDS = 120;
    public static final long DEFAULT_MAX_STALE_SECONDS = 600;
    /** Values stamped further in the future than this are treated as untrustworthy (clock skew). */
    public static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(30);

    private final Clock clock;
    private final long freshSeconds;
    private final long maxStaleSeconds;

    public RealtimeFreshnessPolicy(Clock clock, long freshSeconds, long maxStaleSeconds) {
        if (clock == null) throw new IllegalArgumentException("clock is required");
        if (freshSeconds <= 0) throw new IllegalArgumentException("realtime.freshness.fresh-seconds must be positive: " + freshSeconds);
        if (freshSeconds >= maxStaleSeconds) {
            throw new IllegalArgumentException("realtime.freshness.fresh-seconds (" + freshSeconds
                    + ") must be less than realtime.freshness.max-stale-seconds (" + maxStaleSeconds + ")");
        }
        this.clock = clock;
        this.freshSeconds = freshSeconds;
        this.maxStaleSeconds = maxStaleSeconds;
    }

    /** 120/600 seconds with the given clock (tests and code paths without the Spring bean). */
    public static RealtimeFreshnessPolicy defaults(Clock clock) {
        return new RealtimeFreshnessPolicy(clock, DEFAULT_FRESH_SECONDS, DEFAULT_MAX_STALE_SECONDS);
    }

    public static RealtimeFreshnessPolicy defaults() { return defaults(Clock.systemUTC()); }

    /** The request's evaluation instant; take it once per request. */
    public Instant now() { return clock.instant(); }

    public long freshSeconds() { return freshSeconds; }

    public long maxStaleSeconds() { return maxStaleSeconds; }

    public Duration maxStale() { return Duration.ofSeconds(maxStaleSeconds); }

    public Assessment classify(@Nullable Instant updatedAt, Instant now) {
        if (updatedAt == null) return new Assessment(RealtimeFreshness.UNAVAILABLE, null);
        if (updatedAt.isAfter(now.plus(MAX_CLOCK_SKEW))) return new Assessment(RealtimeFreshness.UNAVAILABLE, 0L);
        // Duration.getSeconds() floors toward negative infinity, so 120.9 s is 120 and -0.5 s is -1 (clamped to 0).
        long age = Math.max(0, Duration.between(updatedAt, now).getSeconds());
        RealtimeFreshness status = age <= freshSeconds ? RealtimeFreshness.FRESH
                : age <= maxStaleSeconds ? RealtimeFreshness.STALE : RealtimeFreshness.UNAVAILABLE;
        return new Assessment(status, age);
    }

    /** Status plus whole-second age; the age is null only when there was no collection time at all. */
    public record Assessment(RealtimeFreshness status, @Nullable Long ageSeconds) {}
}
