package com.ddareungi.realtime.application;

import com.ddareungi.realtime.domain.RealtimeStationStatus;

import java.util.List;
import java.util.Map;

/**
 * Read side of the realtime station snapshot (spec v5 21.2, roadmap R2).
 *
 * <p>Implementations return only entries that are present, well formed, publicly eligible and no older than
 * {@code realtime.freshness.max-stale-seconds} (or at most 30 s in the future). A station that is missing, expired
 * or corrupt is simply absent from the map, so callers treat its count as unknown (null) and never as zero bikes.
 * Callers classify FRESH/STALE themselves with {@link com.ddareungi.realtime.domain.RealtimeFreshnessPolicy}.
 * Storage failures surface as {@link org.springframework.dao.DataAccessException}.
 */
public interface RealtimeSnapshotReader {
    /** Public values plus the ids excluded by the publication gate. */
    RealtimeSnapshot read(List<String> stationIds);

    /** Public values only; same as {@code read(ids).statuses()}. */
    default Map<String, RealtimeStationStatus> findAll(List<String> stationIds) {
        return read(stationIds).statuses();
    }
}
