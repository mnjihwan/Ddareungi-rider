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
    String WALKING = "foot-walking";
    /** Bicycle profile of the engine's default mode (Kakao: {@code BIKE_ONLY}, i.e. bike roads preferred). */
    String CYCLING = "cycling-regular";
    /** Bicycle profile without the bike-road preference (Kakao: {@code SHORTEST}); only if {@link #supportsBikeRoadPreference()}. */
    String CYCLING_SHORTEST = "cycling-shortest";

    boolean configured();
    default RouteProvider forRequest() { return this; }
    default String attribution() { return "openrouteservice · © OpenStreetMap contributors"; }
    /**
     * Whether the engine can route bicycles with and without a bike-road preference (spec 13.1 {@code preferBikeRoad}).
     * Engines that cannot must not silently ignore {@code preferBikeRoad=true} (422 UNSUPPORTED_ROUTE_OPTION).
     */
    default boolean supportsBikeRoadPreference() { return false; }
    /**
     * Durations and distances of every source × destination pair. Provider failures of single pairs are recorded in
     * their cells (A6 partial results); an engine that can only answer the whole matrix at once may throw instead.
     */
    RouteMatrix matrix(String profile, List<Location> sources, List<Location> destinations);
    /** Null entries represent unreachable pairs, never zero-minute routes. Any failed pair is thrown. */
    default Double[][] durations(String profile, List<Location> sources, List<Location> destinations) {
        return matrix(profile, sources, destinations).durations();
    }
    Leg directions(String profile, Location from, Location to);
    record Step(String instruction, double distanceMeters, double durationSeconds) {}
    record Leg(String mode, double distanceMeters, double durationSeconds,
               List<Location> path, List<Step> steps) {}
}
