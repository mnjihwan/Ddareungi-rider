package com.ddareungi.station.infrastructure;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code stationCatalog} health (D4): UP once a station catalog is loaded in memory, DOWN before that. Part of the
 * infra readiness group ({@code application-infra.yml}) so an instance without a catalog takes no traffic. Later
 * reload failures keep the previous catalog and stay UP (details show when it was loaded).
 */
@Profile("infra")
@Component("stationCatalogHealthIndicator")
public class StationCatalogHealthIndicator implements HealthIndicator {
    private final JdbcStationSearchAdapter adapter;

    public StationCatalogHealthIndicator(JdbcStationSearchAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public Health health() {
        StationCatalog catalog = adapter.catalog();
        if (catalog == null) return Health.down().withDetail("loaded", false).build();
        return Health.up().withDetail("loaded", true).withDetail("stations", catalog.publicEntries().size())
                .withDetail("loadedAt", catalog.loadedAt().toString()).build();
    }
}
