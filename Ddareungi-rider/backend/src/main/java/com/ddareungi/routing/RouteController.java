package com.ddareungi.routing;

import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.global.response.Meta;
import com.ddareungi.global.response.WarningCode;
import com.ddareungi.routing.presentation.RoutePlanRequest;
import com.ddareungi.routing.presentation.RoutePlanResponse;
import com.ddareungi.routing.presentation.RouteSearchRequest;
import com.ddareungi.routing.presentation.RouteSearchResponse;
import com.ddareungi.routing.presentation.RouteStatusResponse;
import com.ddareungi.station.application.StationViewMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {
    private final PlanBikeRoute planner;
    private final StationViewMapper stations;
    public RouteController(PlanBikeRoute planner, StationViewMapper stations) {
        this.planner = planner; this.stations = stations;
    }

    @GetMapping("/status")
    public ApiResponse<RouteStatusResponse> status() {
        return ApiResponse.ok(new RouteStatusResponse(planner.configured(), planner.supportsBikeRoadPreference()));
    }

    /** Spec 13.1 route search: recommended + alternatives; partial provider failures are reported in {@code meta}. */
    @PostMapping("/search")
    public ApiResponse<RouteSearchResponse> search(@Valid @RequestBody RouteSearchRequest request) {
        var result = planner.search(request.toCriteria());
        var body = RouteSearchResponse.from(result, stations);
        return result.partial() ? ApiResponse.ok(body, Meta.partial(List.of(WarningCode.PROVIDER_PARTIAL_FAILURE)))
                : ApiResponse.ok(body);
    }

    /**
     * Legacy endpoint kept for one release (phase 3 decision 1): a thin adapter that answers the recommended route of
     * the search service in the former response shape (unchanged).
     */
    @PostMapping("/plan")
    public ApiResponse<RoutePlanResponse> plan(@Valid @RequestBody RoutePlanRequest request) {
        var plan = planner.execute(request.origin().toLocation(), request.destination().toLocation(),
                request.departureStationId(), request.arrivalStationId());
        return ApiResponse.ok(RoutePlanResponse.from(plan, stations));
    }
}
