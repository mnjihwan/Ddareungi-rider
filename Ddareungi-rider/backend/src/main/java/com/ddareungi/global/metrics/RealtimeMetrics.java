package com.ddareungi.global.metrics;

import com.ddareungi.station.domain.RealtimeSummary;
import com.ddareungi.station.domain.StationSearchPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToDoubleFunction;

/**
 * Realtime freshness gauges (phase 3 O8), all from one {@link StationSearchPort#realtimeSummary()} per scrape:
 * <ul>
 *   <li>{@code ddareungi.realtime.snapshot.age.seconds}: now − {@code station:realtime:last-success-at}
 *       (NaN when unknown)</li>
 *   <li>{@code ddareungi.realtime.stations{status=fresh|stale|unavailable}}: public stations per status</li>
 * </ul>
 * The summary is memoized for {@link #MEMO} so the four gauges of one scrape share a single evaluation. While the
 * station catalog is not loaded (the summary throws 503) every gauge reads NaN.
 */
@Component
public class RealtimeMetrics implements MeterBinder {
    public static final String AGE = "ddareungi.realtime.snapshot.age.seconds";
    public static final String STATIONS = "ddareungi.realtime.stations";
    static final Duration MEMO = Duration.ofSeconds(1);

    private final @Nullable StationSearchPort stations;
    private final Clock clock;
    private @Nullable RealtimeSummary cached;
    private @Nullable Instant cachedAt;

    @Autowired
    public RealtimeMetrics(ObjectProvider<StationSearchPort> stations, ObjectProvider<Clock> clock) {
        this(stations.getIfUnique(), clock.getIfUnique(Clock::systemUTC));
    }

    RealtimeMetrics(@Nullable StationSearchPort stations, Clock clock) {
        this.stations = stations;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (stations == null) return; // no station adapter in this profile
        Gauge.builder(AGE, this, metrics -> metrics.ageSeconds())
                .description("Seconds since the last fully successful realtime collection")
                .baseUnit("seconds")
                .register(registry);
        status(registry, "fresh", RealtimeSummary::fresh);
        status(registry, "stale", RealtimeSummary::stale);
        status(registry, "unavailable", RealtimeSummary::unavailable);
    }

    private void status(MeterRegistry registry, String status, ToDoubleFunction<RealtimeSummary> count) {
        Gauge.builder(STATIONS, this, metrics -> {
                    RealtimeSummary summary = metrics.summary();
                    return summary == null ? Double.NaN : count.applyAsDouble(summary);
                })
                .description("Public stations by realtime status")
                .tag("status", status)
                .register(registry);
    }

    double ageSeconds() {
        RealtimeSummary summary = summary();
        if (summary == null || summary.lastSuccessAt() == null) return Double.NaN;
        return Duration.between(summary.lastSuccessAt(), summary.evaluatedAt()).toMillis() / 1000.0;
    }

    synchronized @Nullable RealtimeSummary summary() {
        Instant now = clock.instant();
        if (cachedAt != null && now.isBefore(cachedAt.plus(MEMO)) && !now.isBefore(cachedAt)) return cached;
        RealtimeSummary fresh;
        StationSearchPort stations = this.stations;
        if (stations == null) return null;
        try {
            fresh = stations.realtimeSummary();
        } catch (RuntimeException unavailable) {
            fresh = null; // catalog not loaded yet (503) or storage error: report NaN, never fail the scrape
        }
        cached = fresh;
        cachedAt = now;
        return fresh;
    }
}
