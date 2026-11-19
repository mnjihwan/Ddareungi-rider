package com.ddareungi.station.domain;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import org.jspecify.annotations.Nullable;

/**
 * One public (not excluded) station with its realtime classification, evaluated against one instant per port call
 * by {@link com.ddareungi.realtime.domain.RealtimeFreshnessPolicy}.
 *
 * <ul>
 *   <li>{@code status} FRESH or STALE: {@code station.availableBikeCount()} and {@code station.updatedAt()} are
 *       non-null; {@code ageSeconds} is the whole-second age of {@code updatedAt}.</li>
 *   <li>{@code status} UNAVAILABLE: no servable realtime value; counts, {@code updatedAt}, {@code shared} are null,
 *       name/coordinates come from the catalog, {@code ageSeconds} is null. Never a successful public item.</li>
 * </ul>
 */
public record StationCandidate(Station station, RealtimeFreshness status, @Nullable Long ageSeconds) {
    public StationCandidate {
        if (station == null || status == null) throw new IllegalArgumentException("station and status are required");
    }

    public String stationId() { return station.stationId(); }
}
