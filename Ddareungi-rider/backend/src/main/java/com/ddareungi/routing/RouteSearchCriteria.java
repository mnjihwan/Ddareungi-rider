package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;

/**
 * One route search. {@code pinnedDeparture}/{@code pinnedArrival} are the extension fields
 * {@code departureStationId}/{@code arrivalStationId} (phase 3 decision 2): a pinned station replaces the automatic
 * candidates on that side and is resolved by id, even outside the search radius.
 */
public record RouteSearchCriteria(@Nullable Location origin, @Nullable Location destination, RouteSearchOptions options,
                                  @Nullable String pinnedDeparture, @Nullable String pinnedArrival) {
    public RouteSearchCriteria {
        if (options == null) options = RouteSearchOptions.defaults();
    }
}
