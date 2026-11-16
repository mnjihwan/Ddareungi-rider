package com.ddareungi.station.infrastructure;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code stationCatalog} health (D4): UP once a non-empty station catalog is loaded in memory, DOWN before that or
 * when the loaded catalog has no public station. Part of the
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
        // Review H2: an empty catalog serves nothing, so it is not ready. The holder never installs a zero-row
        // catalog; this also covers a catalog whose rows are all ineligible for publication.
        if (catalog.size() == 0 || catalog.publicEntries().isEmpty()) {
            return Health.down().withDetail("loaded", true).withDetail("empty", true)
                    .withDetail("rows", catalog.size()).withDetail("stations", catalog.publicEntries().size())
                    .withDetail("loadedAt", catalog.loadedAt().toString()).build();
        }
        return Health.up().withDetail("loaded", true).withDetail("stations", catalog.publicEntries().size())
                .withDetail("loadedAt", catalog.loadedAt().toString()).build();
    }
}
