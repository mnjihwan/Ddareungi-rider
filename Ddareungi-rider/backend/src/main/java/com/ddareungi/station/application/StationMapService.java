package com.ddareungi.station.application;

import com.ddareungi.global.response.WarningCode;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationSearchPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Map-area reads (Phase 3 R4): the capped viewport list and server-side grid clusters. Both read the in-memory
 * catalog and realtime snapshot through {@link StationSearchPort} (no MySQL/Redis on the request path) and apply
 * the A3 list rules: UNAVAILABLE stations are never returned and make the response partial, STALE ones are kept
 * and make it partial. A catalog failure propagates from the port (503 via the exception handler).
 */
@Service
public class StationMapService {
    private final StationSearchPort port;
    private final StationViewMapper mapper;
    private final StationMapProperties properties;

    public StationMapService(StationSearchPort port, StationViewMapper mapper, StationMapProperties properties) {
        this.port = port;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * {@code GET /stations/viewport}: unchanged A3 list (distance from the box centre, then stationId) except the
     * cap: above {@link StationMapProperties#viewportMaxItems()} FRESH/STALE stations, the nearest ones are returned
     * with {@code RESULT_TRUNCATED}.
     */
    public StationListView viewport(StationBounds bounds) {
        return mapper.toListView(port.findInBounds(bounds), properties.viewportMaxItems());
    }

    /**
     * {@code GET /stations/clusters}. The controller validates {@code level}
     * ({@value StationMapProperties#MIN_CLUSTER_LEVEL}..{@value StationMapProperties#MAX_CLUSTER_LEVEL}); the same
     * check here (IllegalArgumentException → 400 {@code INVALID_PARAMETER}) covers callers without method validation,
     * before the port is touched. No candidate or only UNAVAILABLE candidates is a 200 with empty items (partial in
     * the latter case), as for the viewport.
     */
    public StationClusterView clusters(StationBounds bounds, int level) {
        if (level < StationMapProperties.MIN_CLUSTER_LEVEL || level > StationMapProperties.MAX_CLUSTER_LEVEL) {
            throw new IllegalArgumentException("level은 " + StationMapProperties.MIN_CLUSTER_LEVEL + "~"
                    + StationMapProperties.MAX_CLUSTER_LEVEL + "의 정수여야 합니다.");
        }
        double cellDegrees = properties.cellDegrees(level);
        var result = StationClusters.aggregate(port.findCandidatesInBounds(bounds), cellDegrees);
        var warnings = new ArrayList<WarningCode>(2);
        if (result.anyStale()) warnings.add(WarningCode.STALE_DATA);
        if (result.anyUnavailable()) warnings.add(WarningCode.REALTIME_DATA_UNAVAILABLE);
        return new StationClusterView(level, cellDegrees, result.clusters(), !warnings.isEmpty(), List.copyOf(warnings));
    }
}
