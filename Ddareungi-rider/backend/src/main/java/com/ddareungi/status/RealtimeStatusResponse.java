package com.ddareungi.status;

import com.ddareungi.station.domain.RealtimeSummary;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code GET /api/v1/status/realtime} data (spec 15).
 *
 * @param lastUpdatedAt last fully successful collection ({@code station:realtime:last-success-at}); null when unknown.
 *                      The MySQL {@code ingestion_run} fallback is not implemented (R2 writes on hold).
 * @param ageSeconds    whole seconds from {@code lastUpdatedAt} to the evaluation instant (floor, min 0); null with it
 */
public record RealtimeStatusResponse(
        Status status,
        @Nullable Instant lastUpdatedAt,
        @Nullable Long ageSeconds,
        int totalStationCount,
        int freshStationCount,
        int staleStationCount,
        int unavailableStationCount
) {
    public enum Status {
        /** At least one public station and all of them FRESH. */
        NORMAL,
        /** Some FRESH/STALE values can be served, but not all stations are FRESH. */
        DELAYED,
        /** No servable realtime value (including no public station at all). */
        UNAVAILABLE
    }

    public static RealtimeStatusResponse from(RealtimeSummary summary) {
        Status status;
        if (summary.total() > 0 && summary.fresh() == summary.total()) status = Status.NORMAL;
        else if (summary.fresh() + summary.stale() > 0) status = Status.DELAYED;
        else status = Status.UNAVAILABLE;
        Instant last = summary.lastSuccessAt();
        Long age = last == null ? null : Math.max(0, Duration.between(last, summary.evaluatedAt()).getSeconds());
        return new RealtimeStatusResponse(status, last, age, summary.total(), summary.fresh(), summary.stale(),
                summary.unavailable());
    }
}
