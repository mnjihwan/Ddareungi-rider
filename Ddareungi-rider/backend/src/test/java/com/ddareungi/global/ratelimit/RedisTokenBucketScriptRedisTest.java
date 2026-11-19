package com.ddareungi.global.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the Lua script against a real Redis when {@code RATELIMIT_TEST_REDIS_PORT} is set (e.g. a throwaway
 * {@code redis-server --port 16380}); skipped otherwise, since CI has no Redis.
 */
class RedisTokenBucketScriptRedisTest {
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll static void connect() {
        String port = System.getenv("RATELIMIT_TEST_REDIS_PORT");
        assumeTrue(port != null && !port.isBlank(), "RATELIMIT_TEST_REDIS_PORT not set");
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(port)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll static void close() {
        if (factory != null) factory.destroy();
    }

    private static RedisTokenBucketRateLimiter instance() {
        var unusedFallback = new LocalTokenBucketRateLimiter(Clock.systemUTC(), 10, Duration.ofMinutes(1));
        return new RedisTokenBucketRateLimiter(redis, unusedFallback, Clock.systemUTC(), Duration.ofSeconds(10));
    }

    @Test void twoInstancesShareOneBucketAndTheScriptSetsStateAndTtl() {
        String key = "test:" + UUID.randomUUID();
        var policy = new RateLimitPolicy(3, 6); // one token every 10 s
        var a = instance();
        var b = instance();
        assertThat(a.tryAcquire(key, policy).allowed()).isTrue();
        assertThat(b.tryAcquire(key, policy).allowed()).isTrue();
        assertThat(a.tryAcquire(key, policy).allowed()).isTrue();
        var rejected = b.tryAcquire(key, policy);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(9L, 10L);

        String redisKey = RedisTokenBucketRateLimiter.KEY_PREFIX + key;
        List<Object> state = redis.opsForHash().multiGet(redisKey, List.of("u", "t"));
        long units = Long.parseLong((String) state.get(0));
        long last = Long.parseLong((String) state.get(1));
        assertThat(units).isBetween(0L, TokenBucket.UNITS_PER_TOKEN - 1);
        // Stored time comes from Redis TIME, in microseconds.
        List<?> time = redis.execute((org.springframework.data.redis.core.RedisCallback<List<?>>) connection ->
                List.of(connection.serverCommands().time()));
        long serverMillis = (Long) time.get(0);
        assertThat(Math.abs(last / 1_000 - serverMillis)).isLessThan(5_000);
        // TTL: full refill (30 s) + 1 s.
        Long ttl = redis.getExpire(redisKey);
        assertThat(ttl).isBetween(25L, 31L);
        redis.delete(redisKey);
    }
}
