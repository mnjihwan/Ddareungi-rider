package com.ddareungi.routing;

import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.routing.presentation.RoutePlanRequest;
import com.ddareungi.routing.presentation.RoutePlanResponse;
import com.ddareungi.routing.presentation.RouteStatusResponse;
import com.ddareungi.station.application.StationViewMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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
        return ApiResponse.ok(new RouteStatusResponse(planner.configured()));
    }

    @PostMapping("/plan")
    public ApiResponse<RoutePlanResponse> plan(@Valid @RequestBody RoutePlanRequest request) {
        var plan = planner.execute(request.origin().toLocation(), request.destination().toLocation(),
                request.departureStationId(), request.arrivalStationId());
        return ApiResponse.ok(RoutePlanResponse.from(plan, stations));
    }
}
