package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import java.util.List;

public interface RouteProvider {
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
