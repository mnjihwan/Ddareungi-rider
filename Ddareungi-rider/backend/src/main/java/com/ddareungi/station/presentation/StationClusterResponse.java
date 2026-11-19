package com.ddareungi.station.presentation;

import com.ddareungi.station.application.StationClusterView;
import com.ddareungi.station.application.StationClusters;

import java.util.List;

/**
 * {@code data} of {@code GET /api/v1/stations/clusters?south&north&west&east&level} (Phase 3 R4, extension API).
 * Example: local sample data, {@code south=37.49&north=37.51&west=127.02&east=127.04&level=8}:
 *
 * <pre>{@code
 * {
 *   "success": true,
 *   "data": {
 *     "level": 8,
 *     "cellDegrees": 0.02,
 *     "items": [
 *       {"latitude": 37.4979, "longitude": 127.0276, "stationCount": 1, "parkingBikeTotCnt": 7, "freshCount": 1},
 *       {"latitude": 37.50255, "longitude": 127.0306, "stationCount": 2, "parkingBikeTotCnt": 13, "freshCount": 2}
 *     ]
 *   },
 *   "meta": {"requestId": "..."},
 *   "timestamp": "..."
 * }
 * }</pre>
 * With STALE or left-out UNAVAILABLE stations: {@code "meta": {"requestId": "...", "partial": true, "warnings":
 * [{"code": "STALE_DATA"}, {"code": "REALTIME_DATA_UNAVAILABLE"}]}}. Errors use the common envelope: {@code level}
 * outside 5..8, missing or not an integer → 400 {@code INVALID_PARAMETER} (detail field {@code level}); a coordinate
 * out of range or not finite → 400 {@code INVALID_COORDINATE}; an inverted/empty box or one wider than 1° → 400
 * {@code INVALID_PARAMETER}; catalog not loaded → 503 {@code SERVICE_UNAVAILABLE}; rate limit ({@code stations}
 * group) → 429 {@code RATE_LIMIT_EXCEEDED}.
 *
 * <ul>
 *   <li>{@code latitude}/{@code longitude}: mean position of the cell's FRESH/STALE stations (not the geometric cell
 *       centre), rounded to 1e-7°, so a one-station cluster sits exactly on the station.</li>
 *   <li>{@code cellDegrees} per level (defaults, {@code station.map.cluster-cell-degrees}): 5 → 0.003, 6 → 0.005,
 *       7 → 0.01, 8 → 0.02.</li>
 *   <li>{@code parkingBikeTotCnt}: sum over FRESH and STALE stations (STALE counts are last known values);
 *       UNAVAILABLE stations are neither counted nor summed. {@code freshCount} &le; {@code stationCount}.</li>
 *   <li>Cells are a fixed grid anchored at (0,0) of {@code cellDegrees}; only stations inside the requested box are
 *       aggregated, so cells cut by the box edge hold only their inside part. Items are ordered south→north, then
 *       west→east.</li>
 *   <li>{@code meta.partial=true} with {@code STALE_DATA} (a STALE station aggregated) and/or
 *       {@code REALTIME_DATA_UNAVAILABLE} (an UNAVAILABLE station in the box left out), as for list APIs (A3).</li>
 * </ul>
 */
public record StationClusterResponse(int level, double cellDegrees, List<Item> items) {
    public record Item(double latitude, double longitude, int stationCount, int parkingBikeTotCnt, int freshCount) {
        static Item from(StationClusters.Cluster cluster) {
            return new Item(round7(cluster.latitude()), round7(cluster.longitude()), cluster.stationCount(),
                    cluster.parkingBikeTotCnt(), cluster.freshCount());
        }
    }

    /** 1e-7° (about 1 cm): hides floating-point noise of the mean, e.g. 127.03059999999999 → 127.0306. */
    static double round7(double degrees) {
        return Math.round(degrees * 1e7) / 1e7;
    }

    public static StationClusterResponse from(StationClusterView view) {
        return new StationClusterResponse(view.level(), view.cellDegrees(), view.items().stream().map(Item::from).toList());
    }
}
