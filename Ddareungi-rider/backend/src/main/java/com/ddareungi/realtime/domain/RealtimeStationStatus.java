package com.ddareungi.realtime.domain;

import java.time.Instant;

public record RealtimeStationStatus(
        String stationId,
        String stationName,
        int rackTotalCount,
        int availableBikeCount,
        double sharedRate,
        double latitude,
        double longitude,
        Instant updatedAt
) {}
