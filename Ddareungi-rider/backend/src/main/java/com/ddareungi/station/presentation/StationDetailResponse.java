package com.ddareungi.station.presentation;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.application.StationView;
import com.ddareungi.station.domain.Station;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Station object without a query centre (spec 11.2 detail, 11.3 realtime batch): the {@link StationResponse} fields
 * minus {@code distanceMeters}. Only FRESH/STALE stations are mapped, so {@code parkingBikeTotCnt},
 * {@code realtimeUpdatedAt} and {@code realtimeAgeSeconds} are set; {@code rackTotCnt}, {@code shared} and the
 * {@code address} extension field stay nullable.
 */
public record StationDetailResponse(
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
        @Nullable String address
) {
    public static StationDetailResponse from(StationView view) {
        Station station = view.station();
        var location = station.location();
        return new StationDetailResponse(
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
                station.address());
    }
}
