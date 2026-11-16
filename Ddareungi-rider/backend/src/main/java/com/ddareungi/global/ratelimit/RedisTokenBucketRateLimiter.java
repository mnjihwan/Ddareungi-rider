package com.ddareungi.global.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token bucket shared by all instances through one atomic Lua script per request (phase 2 D6/D7). The script reads
 * the clock with Redis {@code TIME}, so instance clock skew does not matter, and mirrors {@link TokenBucket}'s integer
 * arithmetic. Any Redis error fails open to the instance-local {@code fallback} limiter (same policy, per instance);
 * Redis is then skipped for {@code failureCooldown} so an outage does not add the Redis timeout to every request.
 * Warnings are logged at most once a minute with the number suppressed in between.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);
    static final String KEY_PREFIX = "ratelimit:v1:";
    private static final long WARN_INTERVAL_MILLIS = 60_000L;

    /**
     * KEYS[1] bucket hash ({@code u}: units, {@code t}: last update in microseconds); ARGV[1] capacity,
     * ARGV[2] tokens per minute. Returns {allowed (1/0), microseconds until the next token}.
     */
    static final String LUA = """
            local capacity = tonumber(ARGV[1])
            local perMinute = tonumber(ARGV[2])
            local unitsPerToken = 60000000
            local max = capacity * unitsPerToken
            local fullRefill = math.ceil(max / perMinute)
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000000 + tonumber(time[2])
            local state = redis.call('HMGET', KEYS[1], 'u', 't')
            local units = tonumber(state[1])
            local last = tonumber(state[2])
            if units == nil or last == nil then
              units = max
              last = now
            else
              local elapsed = now - last
              if elapsed < 0 then elapsed = 0 end
              if elapsed > fullRefill then elapsed = fullRefill end
              units = math.min(max, units + elapsed * perMinute)
              if now > last then last = now end
            end
            local allowed = 0
            local wait = 0
            if units >= unitsPerToken then
              units = units - unitsPerToken
              allowed = 1
            else
              wait = math.ceil((unitsPerToken - units) / perMinute)
            end
            redis.call('HSET', KEYS[1], 'u', string.format('%.0f', units), 't', string.format('%.0f', last))
            redis.call('PEXPIRE', KEYS[1], math.ceil(fullRefill / 1000) + 1000)
            return {allowed, wait}
            """;

    @SuppressWarnings({"rawtypes", "unchecked"})
    static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redis;
    private final RateLimiter fallback;
    private final Clock clock;
    private final long failureCooldownMillis;
    private final AtomicLong skipRedisUntilMillis = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastWarnMillis = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressedWarnings = new AtomicLong();
    private final AtomicBoolean degraded = new AtomicBoolean();

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, RateLimiter fallback, Clock clock,
                                       Duration failureCooldown) {
        this.redis = redis;
        this.fallback = fallback;
        this.clock = clock;
        this.failureCooldownMillis = failureCooldown.toMillis();
    }

    @Override
    public RateLimitDecision tryAcquire(String key, RateLimitPolicy policy) {
        long now = clock.millis();
        if (now < skipRedisUntilMillis.get()) return fallback.tryAcquire(key, policy);
        List<?> result;
        try {
            result = redis.execute(SCRIPT, List.of(KEY_PREFIX + key),
                    Integer.toString(policy.capacity()), Integer.toString(policy.perMinute()));
        } catch (RuntimeException exception) {
            onFailure(now, exception.toString());
            return fallback.tryAcquire(key, policy);
        }
        if (result == null || result.size() < 2 || !(result.get(0) instanceof Number allowed)
                || !(result.get(1) instanceof Number wait)) {
            onFailure(now, "unexpected script result " + result);
            return fallback.tryAcquire(key, policy);
        }
        if (degraded.compareAndSet(true, false)) log.info("Rate limit store reachable again; shared limits restored");
        return allowed.longValue() == 1 ? RateLimitDecision.ALLOW : RateLimitDecision.reject(wait.longValue());
    }

    private void onFailure(long now, String reason) {
        skipRedisUntilMillis.set(now + failureCooldownMillis);
        degraded.set(true);
        long last = lastWarnMillis.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MILLIS) && lastWarnMillis.compareAndSet(last, now)) {
            log.warn("Rate limit store unavailable; using instance-local limits (Redis skipped for {} ms, "
                    + "{} similar warnings suppressed): {}", failureCooldownMillis, suppressedWarnings.getAndSet(0), reason);
        } else {
            suppressedWarnings.incrementAndGet();
        }
    }
}
