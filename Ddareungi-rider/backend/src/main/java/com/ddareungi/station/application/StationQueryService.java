package com.ddareungi.station.application;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import com.ddareungi.station.domain.StationSearchPort;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Nearby (A5) on top of {@link StationSearchPort#findWithin}, station detail and realtime batch (A4) on top of
 * {@link StationSearchPort#lookup}. The port classifies every
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
     * {@code GET /stations/nearby} (spec 11.1 and the 11.0 decision table). Order: every candidate in the radius with
     * its status → filter → sort → limit, so a bike-count or name sort ranks the whole radius, not a nearest subset.
     *
     * <ul>
     *   <li>No candidate → 200, empty, not partial.</li>
     *   <li>Candidates but none FRESH/STALE → 503 {@code REALTIME_DATA_UNAVAILABLE}.</li>
     *   <li>{@code onlyAvailable}: FRESH with {@code parkingBikeTotCnt > 0} only; no FRESH candidate at all (STALE
     *       and/or UNAVAILABLE only) → 503. FRESH present but none positive → 200 empty.</li>
     *   <li>partial: a returned item is STALE ({@code STALE_DATA}), or (onlyAvailable) a STALE candidate was left out
     *       as unconfirmed ({@code STALE_DATA}), or any candidate in the radius is UNAVAILABLE
     *       ({@code REALTIME_DATA_UNAVAILABLE}). Truncation by {@code limit} alone is never partial.</li>
     * </ul>
     */
    public NearbyStationsView nearby(NearbyQuery query) {
        List<StationCandidate> candidates = port.findWithin(query.center(), query.radiusMeters());
        boolean anyFresh = false;
        boolean anyStale = false;
        boolean anyUnavailable = false;
        for (StationCandidate candidate : candidates) {
            switch (candidate.status()) {
                case FRESH -> anyFresh = true;
                case STALE -> anyStale = true;
                case UNAVAILABLE -> anyUnavailable = true;
            }
        }
        if (!candidates.isEmpty() && (query.onlyAvailable() ? !anyFresh : !anyFresh && !anyStale)) {
            throw new ApiException(ErrorCode.REALTIME_DATA_UNAVAILABLE);
        }
        List<StationView> items = candidates.stream()
                .filter(candidate -> query.onlyAvailable() ? rentableNow(candidate) : candidate.status().servable())
                .map(Sortable::of)
                .sorted(comparator(query.sort()))
                .limit(query.limit())
                .map(sortable -> view(sortable.candidate()))
                .toList();
        boolean staleReturned = items.stream().anyMatch(item -> item.realtimeStatus() == RealtimeFreshness.STALE);
        boolean staleWarning = query.onlyAvailable() ? anyStale : staleReturned;
        var warnings = new ArrayList<WarningCode>(2);
        if (staleWarning) warnings.add(WarningCode.STALE_DATA);
        if (anyUnavailable) warnings.add(WarningCode.REALTIME_DATA_UNAVAILABLE);
        return new NearbyStationsView(query.center(), query.radiusMeters(), items, !warnings.isEmpty(), warnings);
    }

    /** onlyAvailable: FRESH and at least one bike. STALE/UNAVAILABLE are never judged rentable. */
    private static boolean rentableNow(StationCandidate candidate) {
        Integer bikes = candidate.station().availableBikeCount();
        return candidate.status() == RealtimeFreshness.FRESH && bikes != null && bikes > 0;
    }

    private static Comparator<Sortable> comparator(NearbySort sort) {
        Comparator<Sortable> byDistance = Comparator.comparingDouble(s -> s.candidate().station().distanceMeters());
        Comparator<Sortable> byId = Comparator.comparing(s -> s.candidate().stationId());
        return switch (sort) {
            case DISTANCE -> byDistance.thenComparing(byId);
            case PARKING_BIKE_TOT_CNT -> Comparator.comparing((Sortable s) -> s.candidate().station().availableBikeCount(),
                            Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
                    .thenComparing(byDistance).thenComparing(byId);
            case NAME -> Comparator.comparing(Sortable::nameKey, StationQueryService::compareCodePoints).thenComparing(byId);
        };
    }

    /**
     * Unicode code point order. {@link String#compareTo} compares UTF-16 units, which puts supplementary characters
     * (surrogate pairs) before U+E000–U+FFFF; the spec asks for code point order.
     */
    static int compareCodePoints(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) return Integer.compare(a, b);
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    /** Candidate with its NFC name computed once per request (used only by the name sort). */
    private record Sortable(StationCandidate candidate, String nameKey) {
        static Sortable of(StationCandidate candidate) {
            String name = candidate.station().stationName();
            return new Sortable(candidate, name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFC));
        }
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
