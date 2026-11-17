package com.ddareungi.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * {@code ddareungi.provider.calls} timers of one provider ({@code provider} = place/walking/bicycle), one per
 * {@link ProviderCallOutcome}. All outcomes are registered up front so dashboards see zero rates instead of missing
 * series. Histogram buckets are published for p95 panels. Without a registry every record is a no-op.
 */
public final class ProviderCallMetrics {
    public static final String NAME = "ddareungi.provider.calls";
    private static final ProviderCallMetrics NOOP = new ProviderCallMetrics(Map.of());

    private final Map<ProviderCallOutcome, Timer> timers;

    private ProviderCallMetrics(Map<ProviderCallOutcome, Timer> timers) {
        this.timers = timers;
    }

    public static ProviderCallMetrics of(String provider, @Nullable MeterRegistry registry) {
        if (registry == null) return NOOP;
        Map<ProviderCallOutcome, Timer> timers = new EnumMap<>(ProviderCallOutcome.class);
        for (ProviderCallOutcome outcome : ProviderCallOutcome.values()) {
            timers.put(outcome, Timer.builder(NAME)
                    .description("External provider calls through ResilientCaller (retries included)")
                    .tag("provider", provider)
                    .tag("outcome", outcome.tag())
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(5))
                    .maximumExpectedValue(Duration.ofSeconds(30))
                    .register(registry));
        }
        return new ProviderCallMetrics(timers);
    }

    public static ProviderCallMetrics noop() { return NOOP; }

    public void record(ProviderCallOutcome outcome, long elapsedNanos) {
        Timer timer = timers.get(outcome);
        if (timer != null) timer.record(Duration.ofNanos(Math.max(0, elapsedNanos)));
    }
}
