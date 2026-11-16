package com.ddareungi.global.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.LongSupplier;

/**
 * Minimal count-based circuit breaker (D7: no Resilience4j). CLOSED records the outcome of the last
 * {@code windowSize} calls; at or above the failure-rate threshold (after {@code minimumCalls}) it turns OPEN and
 * rejects calls for {@code openDuration}. Then HALF_OPEN admits {@code halfOpenProbes} probes: all succeeding closes
 * it, any failure reopens it. Outcomes reported with {@link #onIgnored()} (4xx, "no road route", cancellations)
 * never count, but release a probe slot.
 *
 * <p>Generations (review M7): every state transition starts a new generation. {@link #acquire()} hands out the
 * current generation as the permit, and an outcome reported with an older permit is dropped. Without this a slow
 * call admitted while CLOSED could finish after the breaker opened and turned HALF_OPEN, and be taken for the probe
 * (closing the breaker on its success, or reopening it on its failure, and freeing a probe slot it never held).
 * The permit-less methods report against the current generation (as before).
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);
    private final String name;
    private final ResilienceProperties.Breaker settings;
    private final LongSupplier nanoClock;
    private final boolean[] window;
    private int recorded;
    private int next;
    private int failures;
    private State state = State.CLOSED;
    private long openedAtNanos;
    private int probesInFlight;
    private int probesSucceeded;
    /** Incremented on every state transition; see the class comment. */
    private long generation;

    /** Returned by {@link #acquire()} when the call is not permitted. */
    public static final long DENIED = -1;

    public CircuitBreaker(String name, ResilienceProperties.Breaker settings) {
        this(name, settings, System::nanoTime);
    }

    CircuitBreaker(String name, ResilienceProperties.Breaker settings, LongSupplier nanoClock) {
        this.name = name;
        this.settings = settings;
        this.nanoClock = nanoClock;
        this.window = new boolean[settings.windowSize()];
    }

    public String name() { return name; }

    public synchronized State state() {
        if (state == State.OPEN && openElapsed()) return State.HALF_OPEN;
        return state;
    }

    /** True when a call may proceed. Every permitted call must later report exactly one outcome. */
    public synchronized boolean tryAcquirePermission() {
        return acquire() != DENIED;
    }

    /**
     * Like {@link #tryAcquirePermission()}, but returns the permit (the current generation) to report the outcome
     * with, or {@link #DENIED}. Every permit must later report exactly one outcome.
     */
    public synchronized long acquire() {
        if (state == State.OPEN) {
            if (!openElapsed()) return DENIED;
            transition(State.HALF_OPEN);
            probesInFlight = 0;
            probesSucceeded = 0;
        }
        if (state == State.HALF_OPEN) {
            if (probesInFlight >= settings.halfOpenProbes()) return DENIED;
            probesInFlight++;
        }
        return generation;
    }

    public synchronized void onSuccess() { onSuccess(generation); }

    public synchronized void onFailure() { onFailure(generation); }

    /** The call ended in a way that says nothing about provider health. */
    public synchronized void onIgnored() { onIgnored(generation); }

    /** Outcome of the call admitted with {@code permit}; dropped when the breaker has changed state since. */
    public synchronized void onSuccess(long permit) {
        if (permit != generation) return;
        switch (state) {
            case CLOSED -> record(false);
            case HALF_OPEN -> {
                probesInFlight = Math.max(0, probesInFlight - 1);
                if (++probesSucceeded >= settings.halfOpenProbes()) close();
            }
            case OPEN -> { /* Unreachable: OPEN always has a newer generation than any permit. */ }
        }
    }

    public synchronized void onFailure(long permit) {
        if (permit != generation) return;
        switch (state) {
            case CLOSED -> {
                record(true);
                if (recorded >= settings.minimumCalls()
                        && failures * 100L >= (long) settings.failureRateThreshold() * recorded) open();
            }
            case HALF_OPEN -> open();
            case OPEN -> { /* Already open. */ }
        }
    }

    public synchronized void onIgnored(long permit) {
        if (permit != generation) return;
        if (state == State.HALF_OPEN) probesInFlight = Math.max(0, probesInFlight - 1);
    }

    private void record(boolean failure) {
        if (recorded == window.length) {
            if (window[next]) failures--;
        } else {
            recorded++;
        }
        window[next] = failure;
        if (failure) failures++;
        next = (next + 1) % window.length;
    }

    private void open() {
        transition(State.OPEN);
        openedAtNanos = nanoClock.getAsLong();
        resetWindow();
    }

    private void close() {
        transition(State.CLOSED);
        resetWindow();
    }

    private void resetWindow() {
        recorded = 0;
        next = 0;
        failures = 0;
        probesInFlight = 0;
        probesSucceeded = 0;
    }

    private boolean openElapsed() { return nanoClock.getAsLong() - openedAtNanos >= settings.openDuration().toNanos(); }

    private void transition(State target) {
        if (state != target) {
            log.warn("Circuit breaker {}: {} -> {}", name, state, target);
            generation++;
        }
        state = target;
    }
}
