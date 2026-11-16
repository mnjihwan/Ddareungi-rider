package com.ddareungi.global.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/**
 * Retry, circuit breaker and bulkhead settings shared by the external providers (D7, D8). Values are a first
 * estimate to be tuned with production measurements.
 */
@ConfigurationProperties("provider.resilience")
public record ResilienceProperties(@DefaultValue Retry retry, @DefaultValue Breaker circuitBreaker,
                                   @DefaultValue Bulkhead bulkhead) {

    public static ResilienceProperties defaults() {
        return new Binder().bindOrCreate("provider.resilience", ResilienceProperties.class);
    }

    /** Idempotent GETs only; retried on timeout, 5xx and connection errors, never on 4xx (including 429). */
    public record Retry(@DefaultValue("1") int maxRetries, @DefaultValue("100ms") Duration delay) {
        public Retry {
            if (maxRetries < 0) throw new IllegalArgumentException("provider.resilience.retry.max-retries must not be negative");
            if (delay == null || delay.isNegative()) throw new IllegalArgumentException("provider.resilience.retry.delay must not be negative");
        }
    }

    /**
     * Count-based window: once {@code minimumCalls} outcomes are recorded and at least
     * {@code failureRateThreshold} percent of the last {@code windowSize} failed, the breaker opens for
     * {@code openDuration}, then lets {@code halfOpenProbes} probe calls through.
     */
    public record Breaker(@DefaultValue("20") int windowSize, @DefaultValue("10") int minimumCalls,
                          @DefaultValue("50") int failureRateThreshold, @DefaultValue("30s") Duration openDuration,
                          @DefaultValue("1") int halfOpenProbes) {
        public Breaker {
            if (windowSize < 1 || minimumCalls < 1 || minimumCalls > windowSize) {
                throw new IllegalArgumentException("provider.resilience.circuit-breaker needs 1 <= minimum-calls <= window-size");
            }
            if (failureRateThreshold < 1 || failureRateThreshold > 100) {
                throw new IllegalArgumentException("provider.resilience.circuit-breaker.failure-rate-threshold must be 1..100");
            }
            if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
                throw new IllegalArgumentException("provider.resilience.circuit-breaker.open-duration must be positive");
            }
            if (halfOpenProbes < 1) throw new IllegalArgumentException("provider.resilience.circuit-breaker.half-open-probes must be positive");
        }
    }

    /** Concurrent in-flight calls per provider; a caller waits at most {@code maxWait} for a slot. */
    public record Bulkhead(@DefaultValue("32") int placeMaxConcurrentCalls,
                           @DefaultValue("48") int routeMaxConcurrentCalls,
                           @DefaultValue("100ms") Duration maxWait) {
        public Bulkhead {
            if (placeMaxConcurrentCalls < 1 || routeMaxConcurrentCalls < 1) {
                throw new IllegalArgumentException("provider.resilience.bulkhead limits must be positive");
            }
            if (maxWait == null || maxWait.isNegative()) throw new IllegalArgumentException("provider.resilience.bulkhead.max-wait must not be negative");
        }
    }
}
