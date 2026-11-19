package com.ddareungi.place.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/**
 * Redis place cache (D9). Only active with the {@code infra} profile; {@code enabled=false} turns it off there.
 *
 * @param ttl lifetime of one cached provider answer
 * @param failureBackoff after a Redis error the cache is bypassed for this long, so an unreachable Redis does not
 *                       add its command timeout to every search
 */
@ConfigurationProperties("place.cache")
public record PlaceCacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5m") Duration ttl,
        @DefaultValue("30s") Duration failureBackoff) {

    public PlaceCacheProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("place.cache.ttl must be positive");
        if (failureBackoff == null || failureBackoff.isNegative()) {
            throw new IllegalArgumentException("place.cache.failure-backoff must not be negative");
        }
    }
}
