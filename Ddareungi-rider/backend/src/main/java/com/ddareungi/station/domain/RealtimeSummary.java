package com.ddareungi.station.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Aggregate realtime state over all public catalog stations, evaluated against one instant.
 *
 * @param total         public candidates (excluded stations are not counted); {@code fresh + stale + unavailable}
 * @param lastSuccessAt time of the last fully successful collection ({@code station:realtime:last-success-at}),
 *                      null when unknown or unreadable. Not derived from individual station values.
 * @param evaluatedAt   the instant the classification used
 */
public record RealtimeSummary(int total, int fresh, int stale, int unavailable,
                              @Nullable Instant lastSuccessAt, Instant evaluatedAt) {
    public RealtimeSummary {
        if (fresh < 0 || stale < 0 || unavailable < 0 || total != fresh + stale + unavailable) {
            throw new IllegalArgumentException("total must equal fresh + stale + unavailable");
        }
    }
}
