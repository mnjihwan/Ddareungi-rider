package com.ddareungi.routing.presentation;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.routing.RouteProvider;
import com.ddareungi.routing.RouteSearchResult;
import com.ddareungi.station.application.StationView;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.presentation.StationResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/routes/search} data (spec 13.1): {@code recommended} plus at most {@code maxCandidates - 1}
 * {@code alternatives}. {@code attribution} is an extension field (phase 3 decision 2); the former origin/destination
 * echo is not part of this response.
 */
public record RouteSearchResponse(RouteResponse recommended, List<RouteResponse> alternatives, String attribution) {

    public enum RentalAvailability { AVAILABLE_AT_FETCH, UNAVAILABLE_AT_FETCH, UNKNOWN }
    public enum ReturnAvailability { UNKNOWN }
    public enum SegmentType { WALK, BIKE }

    /**
     * Totals are sums of the segment values rounded to integers (phase 3 decision 8). Lock/unlock and waiting times
     * are not included.
     */
    public record RouteResponse(
            String routeId,
            long totalDistanceMeters,
            long estimatedDurationSeconds,
            long walkingDistanceMeters,
            long bikeDistanceMeters,
            StationResponse originStation,
            StationResponse destinationStation,
            List<SegmentResponse> segments,
            RentalAvailability rentalAvailability,
            ReturnAvailability returnAvailability
    ) {}

    /**
     * {@code bikeRoadPreferred} only on BIKE segments: whether the preference was applied, not a bike-road ratio.
     * {@code steps} is an extension field (turn-by-turn guidance from the provider).
     */
    public record SegmentResponse(
            SegmentType type,
            long distanceMeters,
            long durationSeconds,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Boolean bikeRoadPreferred,
            String encodedPolyline,
            List<RoutePlanResponse.StepResponse> steps
    ) {}

    /**
     * Both stations of every route are classified by {@link StationViewMapper} at one instant, and
     * {@code rentalAvailability} is derived from that same view, so status and availability never disagree.
     */
    public static RouteSearchResponse from(RouteSearchResult result, StationViewMapper stations) {
        Instant now = stations.now();
        var routes = result.ranked().stream().map(route -> route(route, result.bikeRoadPreferred(), stations, now)).toList();
        return new RouteSearchResponse(routes.getFirst(), routes.subList(1, routes.size()), result.attribution());
    }

    private static RouteResponse route(RouteSearchResult.Route route, boolean bikeRoadPreferred,
                                       StationViewMapper stations, Instant now) {
        var segments = route.legs().stream().map(leg -> segment(leg, bikeRoadPreferred)).toList();
        long walking = segments.stream().filter(s -> s.type() == SegmentType.WALK).mapToLong(SegmentResponse::distanceMeters).sum();
        long bike = segments.stream().filter(s -> s.type() == SegmentType.BIKE).mapToLong(SegmentResponse::distanceMeters).sum();
        var origin = stations.toView(route.departure(), now);
        return new RouteResponse(routeId(), walking + bike,
                segments.stream().mapToLong(SegmentResponse::durationSeconds).sum(), walking, bike,
                StationResponse.from(origin), StationResponse.from(stations.toView(route.arrival(), now)),
                segments, rentalAvailability(origin), ReturnAvailability.UNKNOWN);
    }

    /** Spec 13.1: FRESH and positive → AVAILABLE_AT_FETCH, FRESH and 0 → UNAVAILABLE_AT_FETCH, otherwise UNKNOWN. */
    static RentalAvailability rentalAvailability(StationView view) {
        Integer bikes = view.station().availableBikeCount();
        if (view.realtimeStatus() != RealtimeFreshness.FRESH || bikes == null) return RentalAvailability.UNKNOWN;
        return bikes > 0 ? RentalAvailability.AVAILABLE_AT_FETCH : RentalAvailability.UNAVAILABLE_AT_FETCH;
    }

    private static SegmentResponse segment(RouteProvider.Leg leg, boolean bikeRoadPreferred) {
        boolean bike = "bicycle".equals(leg.mode());
        // A 0-distance WALK still has two points (spec 13.1: every route keeps WALK → BIKE → WALK).
        var path = leg.path() == null ? List.<com.ddareungi.station.domain.Location>of() : leg.path();
        if (path.size() == 1) path = List.of(path.getFirst(), path.getFirst());
        return new SegmentResponse(bike ? SegmentType.BIKE : SegmentType.WALK,
                Math.round(leg.distanceMeters()), Math.round(leg.durationSeconds()),
                bike ? bikeRoadPreferred : null, EncodedPolyline.encode(path),
                leg.steps() == null ? List.of() : leg.steps().stream().map(RoutePlanResponse.StepResponse::from).toList());
    }

    /** {@code route-} + the first 12 hex digits of a random UUID; for tracing only, never stored. */
    static String routeId() {
        return "route-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
