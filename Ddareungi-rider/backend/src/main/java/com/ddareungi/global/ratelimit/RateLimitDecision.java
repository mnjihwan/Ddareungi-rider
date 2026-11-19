package com.ddareungi.global.ratelimit;

/** Result of one token request: allowed, or rejected with a positive Retry-After in whole seconds. */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
    static final RateLimitDecision ALLOW = new RateLimitDecision(true, 0);

    public RateLimitDecision {
        if (!allowed && retryAfterSeconds < 1) {
            throw new IllegalArgumentException("a rejection needs a positive Retry-After");
        }
    }

    /** A rejection that may be retried after {@code waitMicros}, rounded up to whole seconds and at least 1. */
    static RateLimitDecision reject(long waitMicros) {
        return new RateLimitDecision(false, Math.max(1, (waitMicros + 999_999) / 1_000_000));
    }
}
