package com.ddareungi.realtime.application;

import java.time.Instant;

/**
 * Memory-only access to the current realtime snapshot for request threads (R3): implementations never perform
 * storage I/O here (zero Redis round trips on the request path). A background poller keeps the snapshot current.
 */
@FunctionalInterface
public interface RealtimeSnapshotSource {
    /**
     * @param now the request's evaluation instant; cached sources ignore it, the local sample source stamps its
     *            values with it
     */
    CachedRealtimeSnapshot current(Instant now);
}
