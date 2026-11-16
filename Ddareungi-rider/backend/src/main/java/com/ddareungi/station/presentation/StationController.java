package com.ddareungi.station.presentation;

import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.global.response.Meta;
import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.application.FindNearbyStationsUseCase;
import com.ddareungi.station.application.StationIds;
import com.ddareungi.station.application.StationQueryService;
import com.ddareungi.station.application.StationView;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Station;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    private final FindNearbyStationsUseCase useCase;
    private final StationViewMapper mapper;
    private final StationQueryService queries;

    public StationController(FindNearbyStationsUseCase useCase, StationViewMapper mapper, StationQueryService queries) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.queries = queries;
    }

    @GetMapping("/nearby")
    public ApiResponse<List<StationResponse>> nearby(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double latitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam(defaultValue = "1000") @Min(100) @Max(5000) int radiusMeters
    ) {
        return toResponses(useCase.execute(latitude, longitude, radiusMeters));
    }

    @PostMapping("/batch")
    public ApiResponse<List<StationResponse>> batch(@Valid @RequestBody StationBatchRequest request) {
        return toResponses(useCase.findByIds(request.stationIds(), request.latitude(), request.longitude()));
    }

    @GetMapping("/viewport")
    public ApiResponse<List<StationResponse>> viewport(@RequestParam double south, @RequestParam double north,
            @RequestParam double west, @RequestParam double east) {
        return toResponses(useCase.findInBounds(south, north, west, east));
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
     * paths (nearby, viewport, search, realtime) win over this pattern by Spring's specificity rules.
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
