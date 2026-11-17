package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import java.time.Instant;
import java.util.List;

/**
 * Ranked routes of one search, best first (the first is {@code recommended}). Never empty.
 *
 * @param partial           some candidate pairs or alternatives could not be computed because a provider failed
 *                          ({@code meta.partial} + {@code PROVIDER_PARTIAL_FAILURE}); never set for "no road route"
 * @param bikeRoadPreferred whether the bike-road preference was applied to the BIKE segments
 */
public record RouteSearchResult(Location origin, Location destination, List<Route> ranked, boolean partial,
                                boolean bikeRoadPreferred, Instant generatedAt, String attribution) {
    public RouteSearchResult {
        if (ranked.isEmpty()) throw new IllegalArgumentException("a search result has at least one route");
        ranked = List.copyOf(ranked);
    }

    public Route recommended() { return ranked.getFirst(); }

    public List<Route> alternatives() { return ranked.subList(1, ranked.size()); }

    /** WALK → BIKE → WALK legs (0-distance walks included) between the two stations. */
    public record Route(Station departure, Station arrival, List<RouteProvider.Leg> legs) {
        public Route { legs = List.copyOf(legs); }
    }
}
