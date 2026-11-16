package com.ddareungi.station.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Station metadata joined with its realtime value when one is servable.
 *
 * @param rackTotalCount     rack count; null when unknown (never 0 for "unknown")
 * @param availableBikeCount rentable bikes at the last servable collection; null while unknown
 * @param distanceMeters     distance from the query centre; 0 when the query has no centre
 * @param updatedAt          original collection time of the realtime value; null when there is none
 * @param shared             source occupancy ratio (%), nullable, kept as is above 100
 */
public record Station(
        String stationId,
        String stationName,
        String address,
        Location location,
        @Nullable Integer rackTotalCount,
        @Nullable Integer availableBikeCount,
        double distanceMeters,
        @Nullable Instant updatedAt,
        @Nullable Double shared
) {
    /** Compatibility constructor without {@code shared} (routing and existing tests); {@code shared} is null. */
    public Station(String stationId, String stationName, String address, Location location,
                   @Nullable Integer rackTotalCount, @Nullable Integer availableBikeCount, double distanceMeters,
                   @Nullable Instant updatedAt) {
        this(stationId, stationName, address, location, rackTotalCount, availableBikeCount, distanceMeters, updatedAt, null);
    }
}
