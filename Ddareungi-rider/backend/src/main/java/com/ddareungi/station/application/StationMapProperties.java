package com.ddareungi.station.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * {@code station.map.*} (Phase 3 R4). Defaults live here ({@code @DefaultValue}); {@code application.yml} needs no
 * entry. Both values shape the public responses of {@code /stations/viewport} and {@code /stations/clusters}.
 *
 * @param viewportMaxItems   cap of {@code /stations/viewport} items (default 500); more FRESH/STALE stations in the
 *                           box return the nearest {@code viewportMaxItems} to the box centre with
 *                           {@code meta.partial=true} and {@code RESULT_TRUNCATED}
 * @param clusterCellDegrees grid cell size in degrees (latitude and longitude alike) for cluster levels
 *                           {@value #MIN_CLUSTER_LEVEL}..{@value #MAX_CLUSTER_LEVEL}, in level order. Default
 *                           0.003/0.005/0.01/0.02 [estimate: about 330 m / 550 m / 1.1 km / 2.2 km north-south in
 *                           Seoul, roughly doubling like the Kakao map scale]. Each value must be in (0, 1] and the
 *                           list non-decreasing.
 */
@ConfigurationProperties("station.map")
public record StationMapProperties(
        @DefaultValue("500") int viewportMaxItems,
        @DefaultValue({"0.003", "0.005", "0.01", "0.02"}) List<Double> clusterCellDegrees) {

    /** Lowest map level served by server clusters; the web shows individual markers at lower levels. */
    public static final int MIN_CLUSTER_LEVEL = 5;
    /** Highest cluster level of the public contract ({@code level=5..8}). */
    public static final int MAX_CLUSTER_LEVEL = 8;

    public StationMapProperties {
        if (viewportMaxItems < 1) {
            throw new IllegalArgumentException("station.map.viewport-max-items must be positive: " + viewportMaxItems);
        }
        int levels = MAX_CLUSTER_LEVEL - MIN_CLUSTER_LEVEL + 1;
        if (clusterCellDegrees == null || clusterCellDegrees.size() != levels) {
            throw new IllegalArgumentException("station.map.cluster-cell-degrees needs " + levels
                    + " values (levels " + MIN_CLUSTER_LEVEL + ".." + MAX_CLUSTER_LEVEL + "): " + clusterCellDegrees);
        }
        double previous = 0;
        for (Double degrees : clusterCellDegrees) {
            if (degrees == null || !(degrees > 0) || degrees > 1 || degrees < previous) {
                throw new IllegalArgumentException(
                        "station.map.cluster-cell-degrees must be in (0, 1] and non-decreasing: " + clusterCellDegrees);
            }
            previous = degrees;
        }
        clusterCellDegrees = List.copyOf(clusterCellDegrees);
    }

    /** The code defaults (tests and manual wiring). */
    public static StationMapProperties defaults() {
        return new StationMapProperties(500, List.of(0.003, 0.005, 0.01, 0.02));
    }

    /**
     * Cell size of a level. Callers validate the public range first (400 {@code INVALID_PARAMETER}); an out-of-range
     * level here is a programming error.
     */
    public double cellDegrees(int level) {
        if (level < MIN_CLUSTER_LEVEL || level > MAX_CLUSTER_LEVEL) {
            throw new IllegalStateException("cluster level out of range: " + level);
        }
        return clusterCellDegrees.get(level - MIN_CLUSTER_LEVEL);
    }
}
