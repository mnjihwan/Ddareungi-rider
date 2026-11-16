package com.ddareungi.global.ratelimit;

/**
 * Immutable token bucket state in exact integer arithmetic, shared by the local limiter and mirrored by the Redis Lua
 * script. One token is {@value RateLimitPolicy#MICROS_PER_MINUTE} units and the bucket gains {@code perMinute} units
 * per microsecond, so refill and Retry-After never suffer from floating point rounding.
 *
 * @param units      stored credit in units (0 .. capacity * UNITS_PER_TOKEN)
 * @param lastMicros time of the last update in microseconds; never moves backwards
 */
record TokenBucket(long units, long lastMicros) {
    static final long UNITS_PER_TOKEN = RateLimitPolicy.MICROS_PER_MINUTE;

    /** A new bucket starts full. */
    static TokenBucket full(RateLimitPolicy policy, long nowMicros) {
        return new TokenBucket(policy.capacity() * UNITS_PER_TOKEN, nowMicros);
    }

    /** Refills up to {@code nowMicros}; a clock that moved backwards adds nothing and keeps the later time. */
    TokenBucket refill(RateLimitPolicy policy, long nowMicros) {
        long max = policy.capacity() * UNITS_PER_TOKEN;
        long elapsed = Math.max(0, nowMicros - lastMicros);
        // Capping elapsed time first keeps the multiplication far from overflow.
        long gained = Math.min(elapsed, policy.fullRefillMicros()) * policy.perMinute();
        return new TokenBucket(Math.min(max, units + gained), Math.max(lastMicros, nowMicros));
    }

    boolean hasToken() { return units >= UNITS_PER_TOKEN; }

    TokenBucket take() { return new TokenBucket(units - UNITS_PER_TOKEN, lastMicros); }

    /** Microseconds until one whole token is available (0 when one already is). */
    long waitMicros(RateLimitPolicy policy) {
        long missing = UNITS_PER_TOKEN - units;
        return missing <= 0 ? 0 : (missing + policy.perMinute() - 1) / policy.perMinute();
    }

    /** True when the bucket would be full by {@code nowMicros}, i.e. dropping it changes nothing. */
    boolean idle(RateLimitPolicy policy, long nowMicros) {
        return refill(policy, nowMicros).units >= policy.capacity() * UNITS_PER_TOKEN;
    }
}
