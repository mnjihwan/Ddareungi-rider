package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;
import java.util.List;

/**
 * Cycling segment of a bike plan (rental station → return station).
 * Provider failures surface as {@code BIKE_ROUTE_PROVIDER_ERROR}; {@code NO_ROAD_ROUTE} keeps its meaning.
 */
public interface BicycleRouteProvider {
    boolean configured();
    String attribution();
    /** A provider scoped to one planning request; it may cache results for that request only. */
    BicycleRouteProvider forRequest();
    /**
     * A request-scoped provider for an explicit bike-road preference (spec 13.1 {@code preferBikeRoad}); null keeps the
     * engine default, as {@link #forRequest()} does.
     */
    BicycleRouteProvider forRequest(@Nullable Boolean preferBikeRoad);
    /** Whether {@code preferBikeRoad} true and false can both be honoured (Kakao: BIKE_ONLY / SHORTEST). */
    boolean supportsBikeRoadPreference();
    /** Null entries represent unreachable pairs, never zero-minute routes. */
    Double[][] durations(List<Location> sources, List<Location> destinations);
    /** Durations, distances and per-pair provider failures (A6); failures carry {@code BIKE_ROUTE_PROVIDER_ERROR}. */
    RouteMatrix matrix(List<Location> sources, List<Location> destinations);
    RouteProvider.Leg route(Location from, Location to);
}
