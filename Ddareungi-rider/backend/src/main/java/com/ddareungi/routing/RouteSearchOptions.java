package com.ddareungi.routing;

import org.jspecify.annotations.Nullable;

/**
 * Search options (spec 13.1 {@code options}). Ranges are validated by the request DTO; this record re-checks them so
 * the planner never runs with an out-of-range value from another caller.
 *
 * @param stationSearchRadiusMeters straight-line search radius around origin and destination (100..5000)
 * @param maxWalkingDistanceMeters  limit of the two WALK segments' route distance sum (0..10000); null = no limit
 *                                  (only the legacy {@code /routes/plan} adapter)
 * @param preferBikeRoad            bike-road preference; null = engine default (legacy adapter: Kakao BIKE_ONLY,
 *                                  ORS regular, never 422)
 * @param excludeStationsWithoutBikes true: departures must be FRESH with bikes (D2 decision table); false: FRESH and
 *                                  STALE departures with any count, flagged by {@code rentalAvailability}
 * @param maxCandidates             routes returned including the recommended one (1..5)
 */
public record RouteSearchOptions(int stationSearchRadiusMeters, @Nullable Integer maxWalkingDistanceMeters,
                                 @Nullable Boolean preferBikeRoad, boolean excludeStationsWithoutBikes,
                                 int maxCandidates) {
    public static final int DEFAULT_RADIUS = 1000;
    public static final int DEFAULT_MAX_WALKING = 1000;
    public static final int DEFAULT_MAX_CANDIDATES = 5;

    public RouteSearchOptions {
        if (stationSearchRadiusMeters < 100 || stationSearchRadiusMeters > 5000)
            throw new IllegalArgumentException("stationSearchRadiusMeters는 100~5000 사이여야 합니다.");
        if (maxWalkingDistanceMeters != null && (maxWalkingDistanceMeters < 0 || maxWalkingDistanceMeters > 10000))
            throw new IllegalArgumentException("maxWalkingDistanceMeters는 0~10000 사이여야 합니다.");
        if (maxCandidates < 1 || maxCandidates > 5)
            throw new IllegalArgumentException("maxCandidates는 1~5 사이여야 합니다.");
    }

    /** Spec 13.1 defaults. */
    public static RouteSearchOptions defaults() {
        return new RouteSearchOptions(DEFAULT_RADIUS, DEFAULT_MAX_WALKING, true, true, DEFAULT_MAX_CANDIDATES);
    }

    /**
     * The former {@code /routes/plan} behaviour (phase 3 decision 1): 1 km, no walking limit, the engine's default
     * bicycle mode and one route (so a vanished departure is still 409 RENTAL_STOCK_CHANGED, as before).
     */
    public static RouteSearchOptions legacyPlan() {
        return new RouteSearchOptions(DEFAULT_RADIUS, null, null, true, 1);
    }
}
