package com.ddareungi.station.application;

import com.ddareungi.global.response.Meta;
import com.ddareungi.global.response.WarningCode;

import java.util.List;

/**
 * Public station list after the A3 rules: only FRESH/STALE items, in the input order.
 *
 * @param partial  true when any STALE item is returned or any UNAVAILABLE item was removed (spec 11.0)
 * @param warnings {@code STALE_DATA} and/or {@code REALTIME_DATA_UNAVAILABLE}; empty when not partial
 */
public record StationListView(List<StationView> items, boolean partial, List<WarningCode> warnings) {
    public StationListView {
        items = List.copyOf(items);
        warnings = List.copyOf(warnings);
    }

    /** Response metadata: {@code partial/warnings} only when partial (requestId filled by ApiResponse). */
    public Meta meta() { return partial ? Meta.partial(warnings) : Meta.of(null); }
}
