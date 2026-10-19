package com.ddareungi.station.domain;

import java.time.Instant;

public record Station(
        String stationId,
        String stationName,
        String address,
        Location location,
        int rackTotalCount,
        Integer availableBikeCount,
        double distanceMeters,
        Instant updatedAt
) {}
