package com.ddareungi.realtime.application;

import com.ddareungi.realtime.domain.RealtimeStationStatus;

import java.util.List;
import java.util.Map;

/**
 * Read side of the realtime station snapshot (spec v5 21.2, roadmap R2).
 *
 * <p>Implementations return only entries that are present, well formed and fresh. A station that is
 * missing, expired or corrupt is simply absent from the map, so callers treat its count as unknown
 * (null) and never as zero bikes. Storage failures surface as {@link org.springframework.dao.DataAccessException}.
 */
public interface RealtimeSnapshotReader {
    Map<String, RealtimeStationStatus> findAll(List<String> stationIds);
}
