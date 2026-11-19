package com.ddareungi.realtime.infrastructure;

import java.util.Locale;

/**
 * {@code realtime.redis.read-mode}. Key names and value format of the versioned layout are a design
 * proposal (spec v5 21.2) and are finalised together with the collector.
 */
public enum RealtimeReadMode {
    /** Versioned layout when {@code station:realtime:current} exists, otherwise the legacy per-station hashes. */
    AUTO,
    /** Only the legacy per-station hashes {@code station:realtime:{stationId}}. */
    LEGACY,
    /** Only {@code station:realtime:current} + {@code station:realtime:v{n}}; no legacy fallback. */
    VERSIONED;

    public static RealtimeReadMode parse(String value) {
        if (value == null || value.isBlank()) return AUTO;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("realtime.redis.read-mode must be one of auto, legacy, versioned: " + value);
        }
    }
}
