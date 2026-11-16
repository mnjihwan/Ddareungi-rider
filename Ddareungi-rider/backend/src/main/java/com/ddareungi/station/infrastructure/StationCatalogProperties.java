package com.ddareungi.station.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code station.catalog.*} (D4): the catalog is loaded synchronously at startup and reloaded every
 * {@code reload-interval} (10 minutes). While no catalog has ever loaded, a reload is retried every
 * {@code retry-interval} (30 seconds) so a startup outage of MySQL does not last ten minutes.
 */
@ConfigurationProperties("station.catalog")
public record StationCatalogProperties(
        @DefaultValue("10m") Duration reloadInterval,
        @DefaultValue("30s") Duration retryInterval) {

    public StationCatalogProperties {
        if (reloadInterval == null || reloadInterval.isNegative() || reloadInterval.isZero()) {
            throw new IllegalArgumentException("station.catalog.reload-interval must be positive: " + reloadInterval);
        }
        if (retryInterval == null || retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("station.catalog.retry-interval must be positive: " + retryInterval);
        }
    }
}
