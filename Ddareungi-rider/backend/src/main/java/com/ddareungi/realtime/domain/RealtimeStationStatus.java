package com.ddareungi.realtime.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * One realtime station value as collected (source field meaning, spec v5 11.0). {@code rackTotalCount} and
 * {@code sharedRate} are nullable: a missing value is unknown, never zero, and does not make the entry
 * incomplete. {@code sharedRate} above 100 is kept as is. {@code updatedAt} is the original collection time.
 */
public record RealtimeStationStatus(
        String stationId,
        String stationName,
        @Nullable Integer rackTotalCount,
        int availableBikeCount,
        @Nullable Double sharedRate,
        double latitude,
        double longitude,
        Instant updatedAt
) {
    /** Compatibility constructor for callers with known (primitive) rack and shared values. */
    public RealtimeStationStatus(String stationId, String stationName, int rackTotalCount, int availableBikeCount,
                                 double sharedRate, double latitude, double longitude, Instant updatedAt) {
        this(stationId, stationName, Integer.valueOf(rackTotalCount), availableBikeCount, Double.valueOf(sharedRate),
                latitude, longitude, updatedAt);
    }
}
