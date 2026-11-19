package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code realtime.freshness.*} (spec v5 11.0, X3 initial values). Validated at startup: {@code fresh-seconds} must
 * be positive and less than {@code max-stale-seconds}, otherwise the context fails to start. The collector's
 * {@code version-ttl-seconds}/carry-over limits must be kept consistent with {@code max-stale-seconds} in the
 * deployment configuration (the backend cannot see them).
 */
@ConfigurationProperties("realtime.freshness")
public record RealtimeFreshnessProperties(
        @DefaultValue("120") long freshSeconds,
        @DefaultValue("600") long maxStaleSeconds) {

    public RealtimeFreshnessProperties {
        if (freshSeconds <= 0) {
            throw new IllegalArgumentException("realtime.freshness.fresh-seconds must be positive: " + freshSeconds);
        }
        if (freshSeconds >= maxStaleSeconds) {
            throw new IllegalArgumentException("realtime.freshness.fresh-seconds (" + freshSeconds
                    + ") must be less than realtime.freshness.max-stale-seconds (" + maxStaleSeconds + ")");
        }
    }

    public RealtimeFreshnessPolicy toPolicy(java.time.Clock clock) {
        return new RealtimeFreshnessPolicy(clock, freshSeconds, maxStaleSeconds);
    }
}
