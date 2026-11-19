package com.ddareungi.global.metrics;

import com.ddareungi.station.infrastructure.StationCatalogHealthIndicator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * In-memory station catalog gauges (phase 3 O8), infra profile only (the local profile has no catalog):
 * <ul>
 *   <li>{@code ddareungi.station.catalog.size}: public stations in the loaded catalog</li>
 *   <li>{@code ddareungi.station.catalog.age.seconds}: now − time the catalog was loaded</li>
 * </ul>
 * Read through the {@code stationCatalog} health indicator's details ({@code stations}, {@code loadedAt}) so the
 * station package stays untouched; both read NaN until a catalog is loaded.
 */
@Component
public class StationCatalogMetrics implements MeterBinder {
    public static final String SIZE = "ddareungi.station.catalog.size";
    public static final String AGE = "ddareungi.station.catalog.age.seconds";

    private final @Nullable StationCatalogHealthIndicator catalog;
    private final Clock clock;

    public StationCatalogMetrics(ObjectProvider<StationCatalogHealthIndicator> catalog, ObjectProvider<Clock> clock) {
        this.catalog = catalog.getIfUnique();
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (catalog == null) return;
        Gauge.builder(SIZE, this, StationCatalogMetrics::size)
                .description("Public stations in the loaded in-memory station catalog")
                .register(registry);
        Gauge.builder(AGE, this, StationCatalogMetrics::ageSeconds)
                .description("Seconds since the in-memory station catalog was loaded")
                .baseUnit("seconds")
                .register(registry);
    }

    double size() {
        Object stations = details("stations");
        return stations instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    double ageSeconds() {
        if (!(details("loadedAt") instanceof String loadedAt)) return Double.NaN;
        try {
            return Duration.between(Instant.parse(loadedAt), clock.instant()).toMillis() / 1000.0;
        } catch (DateTimeParseException invalid) {
            return Double.NaN;
        }
    }

    private @Nullable Object details(String key) {
        if (catalog == null) return null;
        Health health = catalog.health();
        return health == null ? null : health.getDetails().get(key);
    }
}
