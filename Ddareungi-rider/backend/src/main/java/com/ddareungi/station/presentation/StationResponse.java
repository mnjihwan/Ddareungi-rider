package com.ddareungi.station.presentation;

import com.ddareungi.station.domain.Station;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Public station object (spec 11.0, X1 source field names). Phase 1 limits: {@code shared} is always null and
 * {@code realtimeStatus}/{@code realtimeAgeSeconds} are not provided yet (A3); {@code parkingBikeTotCnt} and
 * {@code realtimeUpdatedAt} may be null while unknown. {@code address} is a nullable extension field.
 */
public record StationResponse(
        String stationId,
        String stationName,
        @Nullable Double stationLatitude,
        @Nullable Double stationLongitude,
        @Nullable Integer rackTotCnt,
        @Nullable Integer parkingBikeTotCnt,
        @Nullable Double shared,
        @Nullable Instant realtimeUpdatedAt,
        double distanceMeters,
        @Nullable String address
) {
    public static StationResponse from(Station station) {
        var location = station.location();
        return new StationResponse(
                station.stationId(),
                station.stationName(),
                location == null ? null : location.latitude(),
                location == null ? null : location.longitude(),
                station.rackTotalCount(),
                station.availableBikeCount(),
                null,
                station.updatedAt(),
                station.distanceMeters(),
                station.address());
    }

    public static @Nullable StationResponse fromNullable(@Nullable Station station) {
        return station == null ? null : from(station);
    }
}
