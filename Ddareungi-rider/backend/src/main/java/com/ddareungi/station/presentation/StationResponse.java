package com.ddareungi.station.presentation;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.application.StationView;
import com.ddareungi.station.domain.Station;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Public station object (spec 11.0, X1 source field names). Station lists contain only {@code FRESH}/{@code STALE}
 * items, so there {@code parkingBikeTotCnt}, {@code realtimeUpdatedAt} and {@code realtimeAgeSeconds} are non-null.
 * {@code rackTotCnt} and {@code shared} stay nullable; {@code shared} above 100 is passed through. {@code address}
 * is a nullable extension field.
 */
public record StationResponse(
        String stationId,
        String stationName,
        @Nullable Double stationLatitude,
        @Nullable Double stationLongitude,
        @Nullable Integer rackTotCnt,
        @Nullable Integer parkingBikeTotCnt,
        @Nullable Double shared,
        RealtimeFreshness realtimeStatus,
        @Nullable Instant realtimeUpdatedAt,
        @Nullable Long realtimeAgeSeconds,
        double distanceMeters,
        @Nullable String address
) {
    /**
     * Maps a view classified by {@link com.ddareungi.station.application.StationViewMapper}. Review M8 removed the
     * {@code from(Station)} overload that classified with the default 120/600 s policy: every caller (station
     * lists, route plans) now goes through the mapper and therefore the configured thresholds.
     */
    public static StationResponse from(StationView view) {
        Station station = view.station();
        var location = station.location();
        return new StationResponse(
                station.stationId(),
                station.stationName(),
                location == null ? null : location.latitude(),
                location == null ? null : location.longitude(),
                station.rackTotalCount(),
                station.availableBikeCount(),
                station.shared(),
                view.realtimeStatus(),
                station.updatedAt(),
                view.realtimeAgeSeconds(),
                station.distanceMeters(),
                station.address());
    }
}
