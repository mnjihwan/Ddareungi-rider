package com.ddareungi.station.presentation;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
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
    /** Maps a view classified by {@link com.ddareungi.station.application.StationViewMapper}. */
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

    /**
     * Compatibility mapping for callers without the policy bean (route plan response). Classifies with the default
     * 120/600 s thresholds on the system clock, so a station without a servable value shows {@code UNAVAILABLE}.
     * Station list endpoints use {@link #from(StationView)}; routing should switch to the mapper in E4.
     */
    public static StationResponse from(Station station) {
        var assessment = RealtimeFreshnessPolicy.defaults().classify(station.updatedAt(), Instant.now());
        return from(new StationView(station, assessment.status(), assessment.ageSeconds()));
    }

    public static @Nullable StationResponse fromNullable(@Nullable Station station) {
        return station == null ? null : from(station);
    }
}
