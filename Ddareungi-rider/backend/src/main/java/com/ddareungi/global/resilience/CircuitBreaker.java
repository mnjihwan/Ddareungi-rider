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
        if (state == State.OPEN) {
            if (!openElapsed()) return false;
            transition(State.HALF_OPEN);
            probesInFlight = 0;
            probesSucceeded = 0;
        }
        if (state == State.HALF_OPEN) {
            if (probesInFlight >= settings.halfOpenProbes()) return false;
            probesInFlight++;
        }
        return true;
    }

    public synchronized void onSuccess() {
        switch (state) {
            case CLOSED -> record(false);
            case HALF_OPEN -> {
                probesInFlight = Math.max(0, probesInFlight - 1);
                if (++probesSucceeded >= settings.halfOpenProbes()) close();
            }
            case OPEN -> { /* A call admitted before opening; it no longer matters. */ }
        }
    }

    public synchronized void onFailure() {
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

    /** The call ended in a way that says nothing about provider health. */
    public synchronized void onIgnored() {
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
        if (state != target) log.warn("Circuit breaker {}: {} -> {}", name, state, target);
        state = target;
    }
}
