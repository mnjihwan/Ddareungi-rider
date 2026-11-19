package com.ddareungi.realtime.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * {@code realtime.snapshot.*} (D5): background poll interval of the in-memory realtime snapshot and the legacy
 * layout's safety reload interval (the legacy layout has no version pointer, so it is reloaded when
 * {@code last-success-at} changes and at least this often).
 */
@ConfigurationProperties("realtime.snapshot")
public record RealtimeSnapshotCacheProperties(
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("60s") Duration legacySafetyReload) {

    public RealtimeSnapshotCacheProperties {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("realtime.snapshot.poll-interval must be positive: " + pollInterval);
        }
        if (legacySafetyReload == null || legacySafetyReload.isNegative() || legacySafetyReload.isZero()) {
            throw new IllegalArgumentException("realtime.snapshot.legacy-safety-reload must be positive: " + legacySafetyReload);
        }
    }

    public static RealtimeSnapshotCacheProperties defaults() {
        return new RealtimeSnapshotCacheProperties(Duration.ofSeconds(5), Duration.ofSeconds(60));
    }
}
