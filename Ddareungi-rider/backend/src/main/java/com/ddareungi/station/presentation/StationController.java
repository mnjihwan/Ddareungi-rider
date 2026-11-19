package com.ddareungi.station.presentation;

import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.global.response.Meta;
import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.application.FindNearbyStationsUseCase;
import com.ddareungi.station.application.NearbyQuery;
import com.ddareungi.station.application.NearbySort;
import com.ddareungi.station.application.StationIds;
import com.ddareungi.station.application.StationMapProperties;
import com.ddareungi.station.application.StationMapService;
import com.ddareungi.station.application.StationQueryService;
import com.ddareungi.station.application.StationView;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/stations")
public class StationController {
    private static final String RADIUS_REASON = "100~5000의 정수여야 합니다.";
    private static final String LIMIT_REASON = "1~100의 정수여야 합니다.";
    private final FindNearbyStationsUseCase useCase;
    private final StationViewMapper mapper;
    private static final String LEVEL_REASON = StationMapProperties.MIN_CLUSTER_LEVEL + "~"
            + StationMapProperties.MAX_CLUSTER_LEVEL + "의 정수여야 합니다.";
    private final StationQueryService queries;
    private final StationMapService map;

    public StationController(FindNearbyStationsUseCase useCase, StationViewMapper mapper, StationQueryService queries,
                             StationMapService map) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.queries = queries;
        this.map = map;
    }

    /**
     * Nearby stations (spec 11.1, A5). Range violations of {@code radius} are {@code INVALID_RADIUS} and of
     * {@code limit} {@code INVALID_LIMIT} (GlobalExceptionHandler.validationCode); an unknown {@code sort} is
     * {@code INVALID_PARAMETER}. The Phase 1 {@code radiusMeters} parameter is no longer read (D11, no alias).
     */
    @GetMapping("/nearby")
    public ApiResponse<NearbyStationsResponse> nearby(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double latitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam(defaultValue = "1000") @Min(value = 100, message = RADIUS_REASON)
            @Max(value = 5000, message = RADIUS_REASON) int radius,
            @RequestParam(defaultValue = "30") @Min(value = 1, message = LIMIT_REASON)
            @Max(value = 100, message = LIMIT_REASON) int limit,
            @RequestParam(defaultValue = "distance")
            @Pattern(regexp = NearbySort.PARAMETER_PATTERN, message = "distance, parkingBikeTotCnt, name 중 하나여야 합니다.")
            String sort,
            @RequestParam(defaultValue = "false") boolean onlyAvailable
    ) {
        var query = new NearbyQuery(new Location(latitude, longitude), radius, limit, NearbySort.fromParameter(sort),
                onlyAvailable);
        var view = queries.nearby(query);
        return ApiResponse.ok(NearbyStationsResponse.from(view), view.meta());
    }

    @PostMapping("/batch")
    public ApiResponse<List<StationResponse>> batch(@Valid @RequestBody StationBatchRequest request) {
        return toResponses(useCase.findByIds(request.stationIds(), request.latitude(), request.longitude()));
    }

    /**
     * Stations in a map box (A3 list rules). R4 adds only the cap: above {@code station.map.viewport-max-items}
     * (500) FRESH/STALE stations, the nearest to the box centre are returned with {@code meta.partial=true} and
     * {@code RESULT_TRUNCATED}. Box validation is unchanged ({@link StationBounds}: out-of-range coordinate →
     * {@code INVALID_COORDINATE}, inverted or wider than 1° → {@code INVALID_PARAMETER}).
     */
    @GetMapping("/viewport")
    public ApiResponse<List<StationResponse>> viewport(@RequestParam double south, @RequestParam double north,
            @RequestParam double west, @RequestParam double east) {
        var view = map.viewport(new StationBounds(south, north, west, east));
        return ApiResponse.ok(view.items().stream().map(StationResponse::from).toList(), view.meta());
    }

    /**
     * Server grid clusters for map levels 5..8 (Phase 3 R4, extension API; contract in {@link StationClusterResponse}).
     * The box follows the viewport rules ({@link StationBounds}); {@code level} outside 5..8, missing or not an
     * integer → {@code INVALID_PARAMETER}. Same rate-limit group as every {@code /api/v1/stations/**} path.
     */
    @GetMapping("/clusters")
    public ApiResponse<StationClusterResponse> clusters(@RequestParam double south, @RequestParam double north,
            @RequestParam double west, @RequestParam double east,
            @RequestParam @Min(value = StationMapProperties.MIN_CLUSTER_LEVEL, message = LEVEL_REASON)
            @Max(value = StationMapProperties.MAX_CLUSTER_LEVEL, message = LEVEL_REASON) int level) {
        var view = map.clusters(new StationBounds(south, north, west, east), level);
        return ApiResponse.ok(StationClusterResponse.from(view), view.meta());
    }

    @GetMapping("/search")
    public ApiResponse<List<StationResponse>> search(@RequestParam String query,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double latitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude) {
        return toResponses(useCase.searchByName(query, latitude, longitude));
    }

    /**
     * Realtime status of several stations (spec 11.3). {@code stationIds} is one comma-separated value; parsing and the
     * 1–100 limit (before duplicates are removed) are in {@link StationIds#parseBatch}.
     */
    @GetMapping("/realtime")
    public ApiResponse<StationRealtimeBatchResponse> realtime(@RequestParam String stationIds) {
        var view = queries.realtime(StationIds.parseBatch(stationIds));
        return ApiResponse.ok(StationRealtimeBatchResponse.from(view), view.meta());
    }

    /**
     * Station detail (spec 11.2). The path pattern refuses the literal {@code batch} so that {@code GET /batch} is a
     * 405 for the POST-only batch endpoint instead of a lookup of a station called "batch". The other literal GET
     * paths (nearby, viewport, clusters, search, realtime) win over this pattern by Spring's specificity rules.
     */
    @GetMapping("/{stationId:(?!batch$).+}")
    public ApiResponse<StationDetailResponse> detail(
            @PathVariable String stationId) {
        StationView view = queries.detail(StationIds.requireValid(stationId));
        Meta meta = view.realtimeStatus() == RealtimeFreshness.STALE
                ? Meta.partial(List.of(WarningCode.STALE_DATA)) : Meta.of(null);
        return ApiResponse.ok(StationDetailResponse.from(view), meta);
    }

    /** A3 list rules (UNAVAILABLE removed, STALE kept, meta.partial/warnings) are applied by the mapper. */
    private ApiResponse<List<StationResponse>> toResponses(List<Station> stations) {
        var view = mapper.toListView(stations);
        return ApiResponse.ok(view.items().stream().map(StationResponse::from).toList(), view.meta());
    }

    public record StationBatchRequest(
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 30) String> stationIds,
            @DecimalMin("-90") @DecimalMax("90") double latitude,
            @DecimalMin("-180") @DecimalMax("180") double longitude
    ) {}
}
