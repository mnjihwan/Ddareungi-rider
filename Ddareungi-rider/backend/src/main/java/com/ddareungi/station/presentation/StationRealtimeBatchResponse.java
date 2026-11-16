package com.ddareungi.station.presentation;

import com.ddareungi.station.application.StationRealtimeBatchView;

import java.util.List;

/** {@code GET /stations/realtime} data (spec 11.3). The three id arrays are always present, possibly empty. */
public record StationRealtimeBatchResponse(
        List<StationDetailResponse> items,
        List<String> notFoundStationIds,
        List<String> excludedStationIds,
        List<String> unavailableStationIds
) {
    public static StationRealtimeBatchResponse from(StationRealtimeBatchView view) {
        return new StationRealtimeBatchResponse(view.items().stream().map(StationDetailResponse::from).toList(),
                view.notFound(), view.excluded(), view.unavailable());
    }
}
