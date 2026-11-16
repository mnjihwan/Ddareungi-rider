package com.ddareungi.global.ratelimit;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Rate limit settings ({@code ratelimit.*}, phase 2 D6). Draft numbers until traffic is measured:
 * stations/status 120/min with a burst of 30, places 30/min (burst 30), routes 10/min (burst 10).
 * A group left out of the configuration keeps its own default.
 *
 * @param enabled            master switch; false lets every request through
 * @param stations           station and status APIs (instance-local limiter)
 * @param places             place APIs (Redis token bucket shared by instances on the infra profile)
 * @param routes             route APIs except {@code /routes/status} (Redis token bucket on the infra profile)
 * @param redisFailureCooldown after a Redis error, how long the shared limiter is skipped in favour of the local one
 * @param localMaxEntries    soft cap on local buckets; above it idle buckets are swept and new clients go untracked
 * @param localCleanupInterval how often idle (already full) local buckets are removed
 */
@ConfigurationProperties("ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @Nullable Policy stations,
        @Nullable Policy places,
        @Nullable Policy routes,
        @DefaultValue("10s") Duration redisFailureCooldown,
        @DefaultValue("100000") int localMaxEntries,
        @DefaultValue("60s") Duration localCleanupInterval) {

    static final Policy DEFAULT_STATIONS = new Policy(30, 120);
    static final Policy DEFAULT_PLACES = new Policy(30, 30);
    static final Policy DEFAULT_ROUTES = new Policy(10, 10);

    public RateLimitProperties {
        stations = Policy.merge(stations, DEFAULT_STATIONS);
        places = Policy.merge(places, DEFAULT_PLACES);
        routes = Policy.merge(routes, DEFAULT_ROUTES);
        if (redisFailureCooldown == null || redisFailureCooldown.isNegative()) {
            throw new IllegalArgumentException("ratelimit.redis-failure-cooldown must not be negative");
        }
        if (localMaxEntries < 1) throw new IllegalArgumentException("ratelimit.local-max-entries must be >= 1");
        if (localCleanupInterval == null || localCleanupInterval.isNegative()) {
            throw new IllegalArgumentException("ratelimit.local-cleanup-interval must not be negative");
        }
    }

    /** Defaults applied, for callers that only use the property object. */
    public static RateLimitProperties defaults() {
        return new RateLimitProperties(true, null, null, null, Duration.ofSeconds(10), 100_000, Duration.ofSeconds(60));
    }

    public RateLimitPolicy policy(RateLimitGroup group) {
        Policy policy = switch (group) {
            case STATIONS -> stations;
            case PLACES -> places;
            case ROUTES -> routes;
        };
        // Non-null after the compact constructor.
        return new RateLimitPolicy(policy.burst(), policy.perMinute());
    }

    /**
     * One group's numbers: {@code burst} is the bucket capacity, {@code perMinute} the steady refill rate.
     * Unset fields fall back to the group default.
     */
    public record Policy(@Nullable Integer burst, @Nullable Integer perMinute) {
        static Policy merge(@Nullable Policy configured, Policy fallback) {
            if (configured == null) return fallback;
            int burst = configured.burst() == null ? fallback.burst() : configured.burst();
            int perMinute = configured.perMinute() == null ? fallback.perMinute() : configured.perMinute();
            if (burst < 1) throw new IllegalArgumentException("ratelimit.*.burst must be >= 1");
            if (perMinute < 1) throw new IllegalArgumentException("ratelimit.*.per-minute must be >= 1");
            return new Policy(burst, perMinute);
        }
    }
}
