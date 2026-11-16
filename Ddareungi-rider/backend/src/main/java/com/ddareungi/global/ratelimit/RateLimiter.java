package com.ddareungi.global.ratelimit;

/** Takes one token from the bucket identified by {@code key} (group and hashed client), creating it full if new. */
public interface RateLimiter {
    RateLimitDecision tryAcquire(String key, RateLimitPolicy policy);
}
