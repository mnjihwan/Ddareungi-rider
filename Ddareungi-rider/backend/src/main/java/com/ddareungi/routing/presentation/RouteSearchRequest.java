package com.ddareungi.routing.presentation;

import com.ddareungi.routing.RouteSearchCriteria;
import com.ddareungi.routing.RouteSearchOptions;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /api/v1/routes/search} body (spec 13.1). {@code options} and each option may be omitted (defaults
 * apply); an explicit {@code null} is rejected as 400 INVALID_PARAMETER ("null·형식 오류는 400"). The option is named
 * {@code preferBikeRoad} as in the spec. {@code departureStationId}/{@code arrivalStationId} are extension fields
 * (phase 3 decision 2).
 * <p>Field-based beans, not records: for record (creator) properties Jackson treats a missing property like an
 * explicit null, so {@code Nulls.FAIL} would also reject omitted options.
 */
public final class RouteSearchRequest {
    @JsonProperty @NotNull @Valid
    private RoutePlanRequest.@Nullable Coordinate origin;
    @JsonProperty @NotNull @Valid
    private RoutePlanRequest.@Nullable Coordinate destination;
    @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Valid
    private @Nullable Options options;
    @JsonProperty @Size(max = 30)
    private @Nullable String departureStationId;
    @JsonProperty @Size(max = 30)
    private @Nullable String arrivalStationId;

    // Only the no-argument constructor: an all-arguments one could be picked as a properties creator.
    public RouteSearchRequest() {}

    public RoutePlanRequest.@Nullable Coordinate origin() { return origin; }
    public RoutePlanRequest.@Nullable Coordinate destination() { return destination; }
    public @Nullable Options options() { return options; }
    public @Nullable String departureStationId() { return departureStationId; }
    public @Nullable String arrivalStationId() { return arrivalStationId; }

    /** Spec 13.1 option table; omitted values take the defaults, explicit nulls fail deserialization (400). */
    public static final class Options {
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Min(100) @Max(5000)
        private @Nullable Integer stationSearchRadiusMeters;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Min(0) @Max(10000)
        private @Nullable Integer maxWalkingDistanceMeters;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL)
        private @Nullable Boolean preferBikeRoad;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL)
        private @Nullable Boolean excludeStationsWithoutBikes;
        @JsonProperty @JsonSetter(nulls = Nulls.FAIL) @Min(1) @Max(5)
        private @Nullable Integer maxCandidates;

        public Options() {}

        RouteSearchOptions toOptions() {
            return new RouteSearchOptions(
                    stationSearchRadiusMeters == null ? RouteSearchOptions.DEFAULT_RADIUS : stationSearchRadiusMeters,
                    maxWalkingDistanceMeters == null ? RouteSearchOptions.DEFAULT_MAX_WALKING : maxWalkingDistanceMeters,
                    preferBikeRoad == null || preferBikeRoad,
                    excludeStationsWithoutBikes == null || excludeStationsWithoutBikes,
                    maxCandidates == null ? RouteSearchOptions.DEFAULT_MAX_CANDIDATES : maxCandidates);
        }
    }

    /** Called after bean validation, so origin and destination are present. */
    public RouteSearchCriteria toCriteria() {
        return new RouteSearchCriteria(origin == null ? null : origin.toLocation(),
                destination == null ? null : destination.toLocation(),
                options == null ? RouteSearchOptions.defaults() : options.toOptions(),
                departureStationId, arrivalStationId);
    }
}
