package com.ddareungi.realtime.application;

import com.ddareungi.realtime.domain.RealtimeStationStatus;

import java.util.Map;
import java.util.Set;

/**
 * Result of one realtime read.
 *
 * @param statuses    public values whose age is within {@code max-stale-seconds} (FRESH or STALE at read time),
 *                    keyed by stationId. Ids that are missing, corrupt or too old are absent (unknown, never zero).
 * @param excludedIds ids whose stored value failed the publication gate (versioned layout: anything but
 *                    {@code publicationEligible=true && operationStatus=ACTIVE}). Gate decisions do not depend on
 *                    the value's age, so a closed station is never revived by an old value.
 */
public record RealtimeSnapshot(Map<String, RealtimeStationStatus> statuses, Set<String> excludedIds) {
    public RealtimeSnapshot {
        statuses = Map.copyOf(statuses);
        excludedIds = Set.copyOf(excludedIds);
    }

    public static RealtimeSnapshot empty() { return new RealtimeSnapshot(Map.of(), Set.of()); }
}
