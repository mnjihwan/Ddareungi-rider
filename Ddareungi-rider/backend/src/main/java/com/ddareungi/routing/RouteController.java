package com.ddareungi.routing;

import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.station.domain.Location;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/routes")
public class RouteController {
    private final PlanBikeRoute planner;
    public RouteController(PlanBikeRoute planner) { this.planner = planner; }
    @GetMapping("/status") public ApiResponse<Status> status() { return ApiResponse.ok(new Status(planner.configured())); }
    @PostMapping("/plan") public ApiResponse<PlanBikeRoute.Plan> plan(@RequestBody Request request) {
        return ApiResponse.ok(planner.execute(request.origin(), request.destination(), request.departureStationId(), request.arrivalStationId()));
    }
    public record Status(boolean configured) {}
    public record Request(Location origin, Location destination, String departureStationId, String arrivalStationId) {}
}
