package com.ddareungi.station.application;

import com.ddareungi.global.response.Meta;
import com.ddareungi.global.response.WarningCode;

import java.util.List;

/**
 * {@code /stations/clusters} result (R4).
 *
 * @param partial  A3 rule: true when a STALE station was aggregated ({@code STALE_DATA}) or an UNAVAILABLE station
 *                 in the box was left out ({@code REALTIME_DATA_UNAVAILABLE})
 * @param warnings in that order; empty when not partial
 */
public record StationClusterView(int level, double cellDegrees, List<StationClusters.Cluster> items, boolean partial,
                                 List<WarningCode> warnings) {
    public StationClusterView {
        items = List.copyOf(items);
        warnings = List.copyOf(warnings);
    }

    public Meta meta() { return partial ? Meta.partial(warnings) : Meta.of(null); }
}
