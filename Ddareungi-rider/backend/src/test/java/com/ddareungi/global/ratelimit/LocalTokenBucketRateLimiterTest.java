package com.ddareungi.global.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTokenBucketRateLimiterTest {
    static final Instant T0 = Instant.parse("2026-11-16T00:00:00Z");
    final MutableClock clock = new MutableClock(T0);
    final LocalTokenBucketRateLimiter limiter = new LocalTokenBucketRateLimiter(clock, 1_000, Duration.ofSeconds(60));

    @Test void burstIsAllowedThenRejectedWithRetryAfterOfOneRefill() {
        var policy = new RateLimitPolicy(30, 120); // one token every 0.5 s
        for (int i = 0; i < 30; i++) assertThat(limiter.tryAcquire("k", policy).allowed()).as("request %d", i).isTrue();
        var rejected = limiter.tryAcquire("k", policy);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1); // 0.5 s rounded up to a whole second
    }

    @Test void refillsAtTheSteadyRateExactly() {
        var policy = new RateLimitPolicy(10, 10); // one token every 6 s
        for (int i = 0; i < 10; i++) assertThat(limiter.tryAcquire("k", policy).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", policy).retryAfterSeconds()).isEqualTo(6);

        clock.advance(Duration.ofMillis(5_999));
        var almost = limiter.tryAcquire("k", policy);
        assertThat(almost.allowed()).isFalse();
        assertThat(almost.retryAfterSeconds()).isEqualTo(1); // 1 ms left, still a positive whole second

        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("k", policy).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", policy).allowed()).isFalse();
    }

    @Test void retryAfterReflectsTheWaitForOneToken() {
        var policy = new RateLimitPolicy(1, 1); // one token per minute
        assertThat(limiter.tryAcquire("k", policy).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", policy).retryAfterSeconds()).isEqualTo(60);
        clock.advance(Duration.ofSeconds(59).plusMillis(1));
        assertThat(limiter.tryAcquire("k", policy).retryAfterSeconds()).isEqualTo(1);
    }

    @Test void refillNeverExceedsCapacity() {
        var policy = new RateLimitPolicy(3, 60);
        for (int i = 0; i < 3; i++) limiter.tryAcquire("k", policy);
        clock.advance(Duration.ofHours(5));
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire("k", policy).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", policy).allowed()).isFalse();
    }

    @Test void clockMovingBackwardsAddsNoTokens() {
        var policy = new RateLimitPolicy(1, 1);
        assertThat(limiter.tryAcquire("k", policy).allowed()).isTrue();
        clock.set(T0.minusSeconds(3_600));
        assertThat(limiter.tryAcquire("k", policy).allowed()).isFalse();
        // Time resumes from the later reading: a full minute after T0 refills one token.
        clock.set(T0.plusSeconds(60));
        assertThat(limiter.tryAcquire("k", policy).allowed()).isTrue();
    }

    @Test void keysAreIndependent() {
        var policy = new RateLimitPolicy(1, 1);
        assertThat(limiter.tryAcquire("stations:a", policy).allowed()).isTrue();
        assertThat(limiter.tryAcquire("stations:a", policy).allowed()).isFalse();
        assertThat(limiter.tryAcquire("stations:b", policy).allowed()).isTrue();
        assertThat(limiter.tryAcquire("places:a", policy).allowed()).isTrue();
    }

    @Test void idleBucketsAreSweptOnTheCleanupInterval() {
        var policy = new RateLimitPolicy(2, 120); // full again 1 s after use
        limiter.tryAcquire("a", policy);
        limiter.tryAcquire("b", policy);
        assertThat(limiter.size()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("c", policy); // interval not reached: nothing swept
        assertThat(limiter.size()).isEqualTo(3);

        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire("c", policy); // sweep runs before this request: a, b, c are all full again
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test void sweepKeepsBucketsThatAreStillRefilling() {
        var policy = new RateLimitPolicy(1, 1); // a minute to refill
        limiter.tryAcquire("busy", policy);
        clock.advance(Duration.ofSeconds(30));
        limiter.sweep();
        assertThat(limiter.size()).isEqualTo(1);
        assertThat(limiter.tryAcquire("busy", policy).allowed()).isFalse();
    }

    @Test void fullTableLetsNewClientsThroughUntrackedButStillLimitsKnownOnes() {
        var small = new LocalTokenBucketRateLimiter(clock, 2, Duration.ofMinutes(10));
        var policy = new RateLimitPolicy(1, 1);
        assertThat(small.tryAcquire("a", policy).allowed()).isTrue();
        assertThat(small.tryAcquire("b", policy).allowed()).isTrue();
        assertThat(small.tryAcquire("c", policy).allowed()).isTrue();
        assertThat(small.tryAcquire("c", policy).allowed()).isTrue(); // untracked
        assertThat(small.size()).isEqualTo(2);
        assertThat(small.tryAcquire("a", policy).allowed()).isFalse();

        // Once the tracked buckets are idle, a forced sweep frees room.
        clock.advance(Duration.ofSeconds(61));
        assertThat(small.tryAcquire("c", policy).allowed()).isTrue();
        assertThat(small.tryAcquire("c", policy).allowed()).isFalse();
    }

    @Test void changedPolicyStartsAFreshBucket() {
        assertThat(limiter.tryAcquire("k", new RateLimitPolicy(1, 1)).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", new RateLimitPolicy(2, 1)).allowed()).isTrue();
    }

    @Test void decisionAndPolicyRejectInvalidNumbers() {
        assertThat(RateLimitDecision.reject(1).retryAfterSeconds()).isEqualTo(1);
        assertThat(RateLimitDecision.reject(1_000_000).retryAfterSeconds()).isEqualTo(1);
        assertThat(RateLimitDecision.reject(1_000_001).retryAfterSeconds()).isEqualTo(2);
        assertThat(RateLimitDecision.reject(0).retryAfterSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> new RateLimitDecision(false, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitPolicy(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
