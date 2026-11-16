package com.ddareungi.station.application;

import com.ddareungi.station.domain.Location;

/** Validated input of {@code GET /stations/nearby} (range checks are done by the controller). */
public record NearbyQuery(Location center, int radiusMeters, int limit, NearbySort sort, boolean onlyAvailable) {
    public NearbyQuery {
        if (center == null || sort == null) throw new IllegalArgumentException("center and sort are required");
        if (radiusMeters < 0 || limit < 1) throw new IllegalArgumentException("radius must be >= 0 and limit >= 1");
    }
}
