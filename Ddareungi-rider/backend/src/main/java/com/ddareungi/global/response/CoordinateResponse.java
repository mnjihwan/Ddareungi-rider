package com.ddareungi.global.response;

import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;

/** WGS84 point in responses that are not station objects (places, route endpoints and paths). */
public record CoordinateResponse(double latitude, double longitude) {
    public static @Nullable CoordinateResponse from(@Nullable Location location) {
        return location == null ? null : new CoordinateResponse(location.latitude(), location.longitude());
    }
}
