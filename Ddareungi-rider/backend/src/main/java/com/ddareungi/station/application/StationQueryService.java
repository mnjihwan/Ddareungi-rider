package com.ddareungi.station.application;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import com.ddareungi.station.domain.StationSearchPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Station detail and realtime batch (A4) on top of {@link StationSearchPort#lookup}. The port classifies every
 * station against one instant per call; this service only applies the public response rules (spec 11.0, 11.2, 11.3).
 * Catalog failures propagate from the port (503 {@code SERVICE_UNAVAILABLE} via the exception handler); realtime
 * store failures arrive as UNAVAILABLE outcomes.
 */
@Service
public class StationQueryService {
    private final StationSearchPort port;

    public StationQueryService(StationSearchPort port) {
        this.port = port;
    }

    /**
     * {@code GET /stations/{stationId}}: NOT_FOUND/EXCLUDED → 404 {@code STATION_NOT_FOUND}, UNAVAILABLE → 503
     * {@code REALTIME_DATA_UNAVAILABLE}, FOUND → the FRESH/STALE view (FRESH with 0 bikes is a normal 200).
     */
    public StationView detail(String stationId) {
        List<StationLookup> results = port.lookup(List.of(stationId));
        StationLookup result = results.isEmpty() ? StationLookup.notFound(stationId) : results.getFirst();
        return switch (result.outcome()) {
            case NOT_FOUND, EXCLUDED -> throw new ApiException(ErrorCode.STATION_NOT_FOUND);
            case UNAVAILABLE -> throw new ApiException(ErrorCode.REALTIME_DATA_UNAVAILABLE);
            case FOUND -> view(result.candidate());
        };
    }

    /**
     * {@code GET /stations/realtime}. {@code ids} must already be validated and distinct ({@link StationIds#parseBatch}).
     * 503 {@code REALTIME_DATA_UNAVAILABLE} when at least one id is public but none could be returned; all
     * not-found/excluded is 200 with empty items. partial when any of the three id arrays is non-empty or any
     * returned item is STALE.
     */
    public StationRealtimeBatchView realtime(List<String> ids) {
        var items = new ArrayList<StationView>();
        var notFound = new ArrayList<String>();
        var excluded = new ArrayList<String>();
        var unavailable = new ArrayList<String>();
        boolean stale = false;
        for (StationLookup result : port.lookup(ids)) {
            switch (result.outcome()) {
                case FOUND -> {
                    StationView view = view(result.candidate());
                    stale |= view.realtimeStatus() == RealtimeFreshness.STALE;
                    items.add(view);
                }
                case NOT_FOUND -> notFound.add(result.stationId());
                case EXCLUDED -> excluded.add(result.stationId());
                case UNAVAILABLE -> unavailable.add(result.stationId());
            }
        }
        if (items.isEmpty() && !unavailable.isEmpty()) throw new ApiException(ErrorCode.REALTIME_DATA_UNAVAILABLE);
        var warnings = new ArrayList<WarningCode>(3);
        if (stale) warnings.add(WarningCode.STALE_DATA);
        if (!unavailable.isEmpty()) warnings.add(WarningCode.REALTIME_DATA_UNAVAILABLE);
        if (!excluded.isEmpty()) warnings.add(WarningCode.STATION_EXCLUDED);
        boolean partial = stale || !notFound.isEmpty() || !excluded.isEmpty() || !unavailable.isEmpty();
        return new StationRealtimeBatchView(items, notFound, excluded, unavailable, partial, warnings);
    }

    static StationView view(StationCandidate candidate) {
        return new StationView(candidate.station(), candidate.status(), candidate.ageSeconds());
    }
}
