package com.ddareungi.station.application;

import com.ddareungi.global.response.Meta;
import com.ddareungi.global.response.WarningCode;
import com.ddareungi.station.domain.Location;

import java.util.List;

/** Result of the nearby query: the request centre/radius and the filtered, sorted, limited items. */
public record NearbyStationsView(Location center, int radiusMeters, List<StationView> items, boolean partial,
                                 List<WarningCode> warnings) {
    public NearbyStationsView {
        items = List.copyOf(items);
        warnings = List.copyOf(warnings);
    }

    public Meta meta() { return partial ? Meta.partial(warnings) : Meta.of(null); }
}
