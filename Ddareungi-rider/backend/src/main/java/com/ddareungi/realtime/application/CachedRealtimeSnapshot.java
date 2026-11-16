package com.ddareungi.realtime.application;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * In-memory realtime state served to request threads (R3). Holds every parsed public value regardless of age:
 * callers classify FRESH/STALE/UNAVAILABLE per request with
 * {@link com.ddareungi.realtime.domain.RealtimeFreshnessPolicy}, so a snapshot kept through a Redis outage ages from
 * STALE into UNAVAILABLE on its own.
 *
 * @param snapshot      public values ({@code statuses}) plus gate-excluded ids
 * @param lastSuccessAt cached {@code station:realtime:last-success-at}; null when never read or absent
 * @param loadedAt      when {@code snapshot} was last (re)loaded from storage; null before the first load
 */
public record CachedRealtimeSnapshot(RealtimeSnapshot snapshot, @Nullable Instant lastSuccessAt, @Nullable Instant loadedAt) {
    public CachedRealtimeSnapshot {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
    }

    public static CachedRealtimeSnapshot empty() { return new CachedRealtimeSnapshot(RealtimeSnapshot.empty(), null, null); }
}
