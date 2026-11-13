package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import java.util.List;

/**
 * Walking segments of a bike plan (origin → rental station, return station → destination).
 * Provider failures surface as {@code WALK_ROUTE_PROVIDER_ERROR}; {@code NO_ROAD_ROUTE} keeps its meaning.
 */
public interface WalkingRouteProvider {
    boolean configured();
    String attribution();
    /** A provider scoped to one planning request; it may cache results for that request only. */
    WalkingRouteProvider forRequest();
    /** Null entries represent unreachable pairs, never zero-minute routes. */
    Double[][] durations(List<Location> sources, List<Location> destinations);
    RouteProvider.Leg route(Location from, Location to);
}
