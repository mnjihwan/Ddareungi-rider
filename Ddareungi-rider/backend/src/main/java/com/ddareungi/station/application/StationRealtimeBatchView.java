package com.ddareungi.station.application;

import com.ddareungi.global.response.Meta;
import com.ddareungi.global.response.WarningCode;

import java.util.List;

/**
 * Result of {@code GET /stations/realtime} (spec 11.3). Every distinct requested id is in exactly one of
 * {@code items} (FRESH/STALE), {@code notFound}, {@code excluded} or {@code unavailable}, each in request order.
 */
public record StationRealtimeBatchView(List<StationView> items, List<String> notFound, List<String> excluded,
                                       List<String> unavailable, boolean partial, List<WarningCode> warnings) {
    public StationRealtimeBatchView {
        items = List.copyOf(items);
        notFound = List.copyOf(notFound);
        excluded = List.copyOf(excluded);
        unavailable = List.copyOf(unavailable);
        warnings = List.copyOf(warnings);
    }

    public Meta meta() { return partial ? Meta.partial(warnings) : Meta.of(null); }
}
