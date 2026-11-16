package com.ddareungi.station.presentation;

import com.ddareungi.global.response.CoordinateResponse;
import com.ddareungi.station.application.NearbyStationsView;

import java.util.List;

/** {@code GET /stations/nearby} data (spec 11.1): request centre, radius in metres and the station items. */
public record NearbyStationsResponse(CoordinateResponse center, int radiusMeters, List<StationResponse> items) {
    public static NearbyStationsResponse from(NearbyStationsView view) {
        return new NearbyStationsResponse(CoordinateResponse.from(view.center()), view.radiusMeters(),
                view.items().stream().map(StationResponse::from).toList());
    }
}
