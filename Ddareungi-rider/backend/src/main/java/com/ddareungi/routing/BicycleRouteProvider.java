package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
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
    /** Null entries represent unreachable pairs, never zero-minute routes. */
    Double[][] durations(List<Location> sources, List<Location> destinations);
    RouteProvider.Leg route(Location from, Location to);
}
