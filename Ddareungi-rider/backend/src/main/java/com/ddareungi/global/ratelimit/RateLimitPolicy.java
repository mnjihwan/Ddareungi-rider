package com.ddareungi.global.ratelimit;

/**
 * Token bucket numbers: up to {@code capacity} requests at once, refilled at {@code perMinute} tokens per minute.
 */
public record RateLimitPolicy(int capacity, int perMinute) {
    static final long MICROS_PER_MINUTE = 60_000_000L;

    public RateLimitPolicy {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        if (perMinute < 1) throw new IllegalArgumentException("perMinute must be >= 1");
    }

    /** Refill rate in tokens per microsecond. */
    double tokensPerMicro() { return perMinute / (double) MICROS_PER_MINUTE; }

    /** Time for an empty bucket to become full again; a bucket idle this long is the same as no bucket. */
    long fullRefillMicros() { return (long) Math.ceil(capacity * (double) MICROS_PER_MINUTE / perMinute); }
}
