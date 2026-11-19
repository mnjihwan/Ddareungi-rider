package com.ddareungi.realtime.domain;

/**
 * Freshness of one realtime snapshot value (spec v5 11.0, X3). Decided by {@link RealtimeFreshnessPolicy} from the
 * original collection time, never from the time the value was read or reloaded.
 * <ul>
 *   <li>{@link #FRESH}: age &le; {@code realtime.freshness.fresh-seconds} (120 by default).</li>
 *   <li>{@link #STALE}: fresh &lt; age &le; {@code realtime.freshness.max-stale-seconds} (600 by default). The last
 *       counts are still served, marked STALE. STALE (and a count of 0) never means the station is closed.</li>
 *   <li>{@link #UNAVAILABLE}: older than max-stale, more than 30 seconds in the future, or no valid collection at
 *       all. Internal only: such stations are not returned as successful Station objects.</li>
 * </ul>
 */
public enum RealtimeFreshness {
    FRESH,
    STALE,
    UNAVAILABLE;

    /** FRESH or STALE: counts may be served (with STALE marked as such). */
    public boolean servable() { return this != UNAVAILABLE; }
}
