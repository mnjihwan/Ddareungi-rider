package com.ddareungi.global.resilience;

import com.ddareungi.global.resilience.CircuitBreaker.State;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {
    final AtomicLong now = new AtomicLong();
    // window 4, minimum 4 calls, 50% threshold, open 30s, one probe
    final CircuitBreaker breaker = new CircuitBreaker("test",
            new ResilienceProperties.Breaker(4, 4, 50, Duration.ofSeconds(30), 1), now::get);

    void call(boolean success) {
        assertThat(breaker.tryAcquirePermission()).isTrue();
        if (success) breaker.onSuccess(); else breaker.onFailure();
    }

    @Test void staysClosedBelowMinimumCallsAndBelowThreshold() {
        call(false); call(false); call(false);
        assertThat(breaker.state()).isEqualTo(State.CLOSED); // only 3 of minimum 4 recorded
        breaker.onIgnored();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test void opensAtThresholdAndRejectsUntilOpenDurationElapses() {
        call(true); call(true); call(false); call(false); // 2/4 = 50%
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();
        now.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(breaker.tryAcquirePermission()).isFalse();
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test void slidingWindowForgetsOldFailures() {
        call(false); call(true); call(true); call(true); // 1/4
        call(false); // oldest failure evicted: T,T,T,F = 1/4
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        call(false); // T,T,F,F = 2/4
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test void halfOpenAdmitsOneProbeAndClosesOnSuccess() {
        open();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.tryAcquirePermission()).isFalse(); // only one probe in flight
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        // fresh window after closing: three failures are below the minimum again
        call(false); call(false); call(false);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test void halfOpenFailureReopens() {
        open();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(breaker.tryAcquirePermission()).isTrue();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();
    }

    @Test void ignoredProbeReleasesTheSlotWithoutClosing() {
        open();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(breaker.tryAcquirePermission()).isTrue();
        breaker.onIgnored();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.tryAcquirePermission()).isTrue();
    }

    // ---- Review M7: generations keep outcomes of calls admitted before a transition out of the probe ----
    @Test void outcomeOfACallAdmittedWhileClosedIsDroppedAfterTheBreakerOpens() {
        long old = breaker.acquire();
        open();
        breaker.onFailure(old); // would have re-recorded/reopened under the old rules; now dropped
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        long probe = breaker.acquire();
        assertThat(probe).isNotEqualTo(CircuitBreaker.DENIED).isNotEqualTo(old);
        breaker.onSuccess(old);   // stale success: must not close the breaker
        breaker.onIgnored(old);   // stale ignore: must not free the probe slot
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        assertThat(breaker.acquire()).isEqualTo(CircuitBreaker.DENIED);
        breaker.onSuccess(probe);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test void staleFailureDoesNotReopenAHalfOpenBreaker() {
        long old = breaker.acquire();
        open();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        long probe = breaker.acquire();
        breaker.onFailure(old);
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        breaker.onFailure(probe);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test void probeOutcomeArrivingAfterTheBreakerReopenedIsDropped() {
        open();
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        long probe = breaker.acquire();
        breaker.onFailure(); // current-generation failure (e.g. reported without a permit) reopens
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        breaker.onSuccess(probe);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
        // After closing, permits of the HALF_OPEN generation no longer count in the new CLOSED window.
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        long next = breaker.acquire();
        breaker.onSuccess(next);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        for (int i = 0; i < 4; i++) breaker.onFailure(next);
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test void permitsOfTheSameClosedGenerationAllCount() {
        long a = breaker.acquire(), b = breaker.acquire(), c = breaker.acquire(), d = breaker.acquire();
        assertThat(a).isEqualTo(b).isEqualTo(c).isEqualTo(d);
        breaker.onSuccess(a); breaker.onSuccess(b); breaker.onFailure(c); breaker.onFailure(d);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    private void open() {
        for (int i = 0; i < 4; i++) call(false);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }
}
