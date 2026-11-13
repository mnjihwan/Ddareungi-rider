package com.ddareungi.routing.presentation;

import com.ddareungi.global.response.CoordinateResponse;
import com.ddareungi.routing.PlanBikeRoute;
import com.ddareungi.routing.RouteProvider;
import com.ddareungi.station.presentation.StationResponse;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/** Route plan response: same structure as {@link PlanBikeRoute.Plan}, with stations in the public station format. */
public record RoutePlanResponse(
        @Nullable CoordinateResponse origin,
        @Nullable CoordinateResponse destination,
        @Nullable StationResponse departureStation,
        @Nullable StationResponse arrivalStation,
        List<LegResponse> legs,
        double distanceMeters,
        double durationSeconds,
        Instant generatedAt,
        String attribution
) {
    public static RoutePlanResponse from(PlanBikeRoute.Plan plan) {
        return new RoutePlanResponse(
                CoordinateResponse.from(plan.origin()),
                CoordinateResponse.from(plan.destination()),
                StationResponse.fromNullable(plan.departureStation()),
                StationResponse.fromNullable(plan.arrivalStation()),
                plan.legs().stream().map(LegResponse::from).toList(),
                plan.distanceMeters(),
                plan.durationSeconds(),
                plan.generatedAt(),
                plan.attribution());
    }

    public record LegResponse(String mode, double distanceMeters, double durationSeconds,
                              List<CoordinateResponse> path, List<StepResponse> steps) {
        static LegResponse from(RouteProvider.Leg leg) {
            return new LegResponse(leg.mode(), leg.distanceMeters(), leg.durationSeconds(),
                    leg.path() == null ? List.of() : leg.path().stream().map(CoordinateResponse::from).toList(),
                    leg.steps() == null ? List.of() : leg.steps().stream().map(StepResponse::from).toList());
        }
    }

    public record StepResponse(String instruction, double distanceMeters, double durationSeconds) {
        static StepResponse from(RouteProvider.Step step) {
            return new StepResponse(step.instruction(), step.distanceMeters(), step.durationSeconds());
        }
    }
}
