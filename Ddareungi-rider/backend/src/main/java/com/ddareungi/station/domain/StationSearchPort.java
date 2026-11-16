package com.ddareungi.station.domain;

import java.util.List;

/**
 * Station read port. The first four methods keep their Phase 1 signatures (used by routing and place search);
 * the last three are the Phase 2 contract (P2-1) that R3/A4/A5 build on. R3 replaces the implementations
 * (catalog + grid index + snapshot cache) without changing these signatures or meanings.
 *
 * <h2>Common rules (spec v5 11.0)</h2>
 * <ul>
 *   <li>Stations excluded by the publication gate (versioned value not {@code publicationEligible=true &&
 *       operationStatus=ACTIVE}) are never returned by any method except as {@link StationLookup.Outcome#EXCLUDED}.
 *       Legacy realtime values carry no gate, so only catalog validity applies to them (D3 limitation).</li>
 *   <li>A realtime value is joined only while servable (age &le; {@code realtime.freshness.max-stale-seconds}).
 *       Unknown counts are null, never 0. FRESH/STALE/UNAVAILABLE follows
 *       {@link com.ddareungi.realtime.domain.RealtimeFreshnessPolicy}; each call evaluates against one instant.</li>
 *   <li>A realtime store failure does not throw: affected stations are UNAVAILABLE (the legacy methods return them
 *       with null counts). A catalog (station metadata) failure propagates as an exception.</li>
 * </ul>
 */
public interface StationSearchPort {
    /**
     * Public stations within {@code radiusMeters}, nearest first. Stations without a servable value are included
     * with null counts/updatedAt (callers such as routing decide freshness themselves).
     */
    List<Station> findNearby(Location center, int radiusMeters);

    /** Public stations among {@code ids} (unknown/excluded ids are omitted), distance from {@code center}. */
    List<Station> findByIds(List<String> ids, Location center);

    /** Name/address search over public stations, ranked by {@link StationNameSearch}. */
    List<Station> searchByName(String query, Location center);

    /** Public stations inside {@code bounds}, distance from the bounds centre. */
    List<Station> findInBounds(StationBounds bounds);

    /**
     * Every public candidate within {@code radiusMeters} of {@code center} (great-circle distance, inclusive),
     * each with its FRESH/STALE/UNAVAILABLE status, ordered by distance ascending then stationId. No limit, filter
     * or availability sort is applied: A5 joins, filters ({@code onlyAvailable}), sorts and limits on top, and
     * applies the decision table (all UNAVAILABLE → 503, STALE present → partial). Excluded stations are absent.
     *
     * @param radiusMeters non-negative radius; validation of the public range (100–5000) belongs to the caller
     */
    List<StationCandidate> findWithin(Location center, int radiusMeters);

    /**
     * One result per distinct id in first-occurrence order (duplicates collapsed, ids are not validated here).
     * Distances are 0 because there is no centre. Empty input returns an empty list without touching storage.
     * See {@link StationLookup} for FOUND / NOT_FOUND / EXCLUDED / UNAVAILABLE.
     */
    List<StationLookup> lookup(List<String> ids);

    /**
     * FRESH/STALE/UNAVAILABLE counts over all public catalog stations plus the last successful collection time,
     * for the realtime status API (A4). The simple P2-1 implementation reads every station's realtime value;
     * R3 serves it from the snapshot cache.
     */
    RealtimeSummary realtimeSummary();
}
