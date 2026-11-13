package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import java.util.List;
import java.util.Set;

/**
 * Internal routing-engine SPI (Kakao or ORS, chosen by {@code routing.provider}).
 * Planning code uses {@link WalkingRouteProvider} / {@link BicycleRouteProvider}, which fix the profile
 * and translate {@link #PROVIDER_FAILURE_CODES} into segment-specific provider errors.
 */
public interface RouteProvider {
    /** Engine error codes meaning the external provider failed (outage/invalid response, quota, credentials). */
    Set<String> PROVIDER_FAILURE_CODES = Set.of("ROUTING_UNAVAILABLE", "ROUTING_LIMIT", "ROUTING_AUTH");

    boolean configured();
    default RouteProvider forRequest() { return this; }
    default String attribution() { return "openrouteservice · © OpenStreetMap contributors"; }
    // Null entries represent unreachable pairs, never zero-minute routes.
    Double[][] durations(String profile, List<Location> sources, List<Location> destinations);
    Leg directions(String profile, Location from, Location to);
    record Step(String instruction, double distanceMeters, double durationSeconds) {}
    record Leg(String mode, double distanceMeters, double durationSeconds,
               List<Location> path, List<Step> steps) {}
}
