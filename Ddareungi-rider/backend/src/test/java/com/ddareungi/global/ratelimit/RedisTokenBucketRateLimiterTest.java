package com.ddareungi.global.ratelimit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenBucketRateLimiterTest {
    static final RateLimitPolicy POLICY = new RateLimitPolicy(2, 30);

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T00:00:00Z"));
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final LocalTokenBucketRateLimiter fallback = new LocalTokenBucketRateLimiter(clock, 100, Duration.ofMinutes(1));
    final RedisTokenBucketRateLimiter limiter =
            new RedisTokenBucketRateLimiter(redis, fallback, clock, Duration.ofSeconds(10));
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    @BeforeEach void attachLogs() { logs.start(); logger.addAppender(logs); }
    @AfterEach void detachLogs() { logger.detachAppender(logs); }

    @SuppressWarnings("unchecked")
    private void scriptReturns(Object result) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private void scriptFails() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
    }

    @Test void scriptUsesRedisTimeAndReturnsAList() {
        assertThat(RedisTokenBucketRateLimiter.SCRIPT.getResultType()).isEqualTo(List.class);
        String lua = RedisTokenBucketRateLimiter.SCRIPT.getScriptAsString();
        assertThat(lua).contains("redis.call('TIME')").contains("HMGET").contains("PEXPIRE")
                .contains("unitsPerToken = 60000000");
        // The bucket key is the only key, so the script stays single-slot.
        assertThat(lua).doesNotContain("KEYS[2]");
        assertThat(TokenBucket.UNITS_PER_TOKEN).isEqualTo(60_000_000L);
    }

    @SuppressWarnings("unchecked")
    @Test void passesThePrefixedKeyAndPolicyAndMapsTheResult() {
        scriptReturns(List.of(1L, 0L));
        assertThat(limiter.tryAcquire("places:abc", POLICY)).isEqualTo(RateLimitDecision.ALLOW);
        verify(redis).execute(eq(RedisTokenBucketRateLimiter.SCRIPT), eq(List.of("ratelimit:v1:places:abc")),
                eq("2"), eq("30"));

        scriptReturns(List.of(0L, 1_500_001L));
        var rejected = limiter.tryAcquire("places:abc", POLICY);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    @Test void redisFailureFailsOpenToTheLocalBucketAndSkipsRedisDuringCooldown() {
        scriptFails();
        // The local fallback applies the same policy per instance: two allowed, then 429.
        assertThat(limiter.tryAcquire("routes:x", POLICY).allowed()).isTrue();
        assertThat(limiter.tryAcquire("routes:x", POLICY).allowed()).isTrue();
        assertThat(limiter.tryAcquire("routes:x", POLICY).allowed()).isFalse();
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(), any());

        clock.advance(Duration.ofSeconds(10));
        scriptReturns(List.of(1L, 0L));
        assertThat(limiter.tryAcquire("routes:x", POLICY).allowed()).isTrue();
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), any(), any());
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.INFO
                && event.getFormattedMessage().contains("reachable again"));
    }

    @Test void warningsAreSuppressedForAMinute() {
        scriptFails();
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("places:" + i, POLICY);
            clock.advance(Duration.ofSeconds(11)); // past the cooldown, so Redis is tried again each time
        }
        assertThat(warnings()).hasSize(1);
        clock.advance(Duration.ofSeconds(10));
        limiter.tryAcquire("places:z", POLICY);
        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(1).getFormattedMessage()).contains("4 similar warnings suppressed");
    }

    @Test void unexpectedResultAlsoFailsOpen() {
        scriptReturns(List.of("garbage"));
        assertThat(limiter.tryAcquire("places:a", POLICY).allowed()).isTrue();
        assertThat(warnings()).hasSize(1);
    }

    @Test void zeroCooldownStillTriesRedisEveryTime() {
        var eager = new RedisTokenBucketRateLimiter(redis, fallback, clock, Duration.ZERO);
        scriptFails();
        eager.tryAcquire("places:a", POLICY);
        eager.tryAcquire("places:a", POLICY);
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), any(), any());
        verify(redis, never()).opsForValue();
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }
}
