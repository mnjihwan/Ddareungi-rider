package com.ddareungi.routing.presentation;

import com.ddareungi.station.domain.Location;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** {@code POST /api/v1/routes/plan} body. JSON shape is unchanged from before phase 1. */
public record RoutePlanRequest(
        @NotNull @Valid Coordinate origin,
        @NotNull @Valid Coordinate destination,
        @Size(max = 30) @Nullable String departureStationId,
        @Size(max = 30) @Nullable String arrivalStationId
) {
    public record Coordinate(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude
    ) {
        public Location toLocation() { return new Location(latitude, longitude); }
    }
}
