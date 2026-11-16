package com.ddareungi.station.domain;

import org.jspecify.annotations.Nullable;

/**
 * Result of {@link StationSearchPort#lookup} for one requested id.
 *
 * <ul>
 *   <li>{@link Outcome#FOUND}: public and servable; {@code candidate} is FRESH or STALE.</li>
 *   <li>{@link Outcome#NOT_FOUND}: no such station in the catalog; {@code candidate} is null.</li>
 *   <li>{@link Outcome#EXCLUDED}: exists but is not public (publication gate failed, or the catalog row lacks a
 *       valid name/coordinates); {@code candidate} is null. Detail API maps this to 404 like NOT_FOUND.</li>
 *   <li>{@link Outcome#UNAVAILABLE}: public but no servable realtime value (missing, corrupt, older than
 *       max-stale, or the realtime store failed); {@code candidate} carries catalog data with status UNAVAILABLE.
 *       Detail API maps this to 503 {@code REALTIME_DATA_UNAVAILABLE}.</li>
 * </ul>
 */
public record StationLookup(String stationId, Outcome outcome, @Nullable StationCandidate candidate) {
    public enum Outcome { FOUND, NOT_FOUND, EXCLUDED, UNAVAILABLE }

    public StationLookup {
        if (stationId == null || outcome == null) throw new IllegalArgumentException("stationId and outcome are required");
        boolean needsCandidate = outcome == Outcome.FOUND || outcome == Outcome.UNAVAILABLE;
        if (needsCandidate != (candidate != null)) {
            throw new IllegalArgumentException("candidate must be present exactly for FOUND and UNAVAILABLE: " + outcome);
        }
    }

    public static StationLookup notFound(String stationId) { return new StationLookup(stationId, Outcome.NOT_FOUND, null); }

    public static StationLookup excluded(String stationId) { return new StationLookup(stationId, Outcome.EXCLUDED, null); }

    /** FOUND when the candidate is FRESH/STALE, UNAVAILABLE otherwise. */
    public static StationLookup of(StationCandidate candidate) {
        return new StationLookup(candidate.stationId(),
                candidate.status().servable() ? Outcome.FOUND : Outcome.UNAVAILABLE, candidate);
    }
}
