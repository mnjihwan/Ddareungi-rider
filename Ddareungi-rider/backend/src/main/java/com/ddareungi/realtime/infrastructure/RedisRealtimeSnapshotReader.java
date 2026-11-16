package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.application.RealtimeSnapshot;
import com.ddareungi.realtime.application.RealtimeSnapshotReader;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads realtime station status from Redis in one of two layouts (spec v5 21.2; key names and value
 * serialisation are a design proposal until the collector implements R2):
 * <ul>
 *   <li>versioned: {@code GET station:realtime:current} → n, then {@code HMGET station:realtime:v{n} id...};
 *       values are JSON with source field names ({@link RealtimeSnapshotValues#fromVersionedJson}).</li>
 *   <li>legacy: one hash per station {@code station:realtime:{id}} ({@link RedisRealtimeStationStore#saveAll}).</li>
 * </ul>
 * {@code realtime.redis.read-mode=auto} (default) uses the versioned layout once the pointer exists and the
 * legacy layout otherwise, so the backend can be deployed before or after the collector starts writing.
 * When the pointer exists but its version hash does not (expired, collector stopped, rollback to the legacy
 * writer), auto logs a throttled warning and reads the legacy layout instead. The existence check
 * ({@code EXISTS station:realtime:v{n}}) is an extra round trip made only when HMGET returned no value for any
 * requested id. When the version hash exists, ids it lacks stay unknown and are never filled from legacy.
 * versioned mode never falls back. Final behaviour is to be confirmed when the collector implements R2.
 *
 * <p>Values older than {@code realtime.freshness.max-stale-seconds} (600 s by default) or more than 30 s in the
 * future are dropped (UNAVAILABLE); everything in between is returned and classified FRESH/STALE by the caller.
 * Versioned values that fail the publication gate are reported in {@link RealtimeSnapshot#excludedIds()} (D3).
 */
@Profile("infra")
@Component
public class RedisRealtimeSnapshotReader implements RealtimeSnapshotReader {
    static final String KEY_PREFIX = "station:realtime:";
    static final String CURRENT_KEY = "station:realtime:current";
    static final String VERSION_KEY_PREFIX = "station:realtime:v";
    static final Duration MAX_CLOCK_SKEW = RealtimeFreshnessPolicy.MAX_CLOCK_SKEW;
    /** Minimum interval between "pointer without version hash" warnings so every request does not log. */
    static final Duration MISSING_HASH_WARN_INTERVAL = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeSnapshotReader.class);

    private final StringRedisTemplate redisTemplate;
    private final RealtimeReadMode mode;
    private final RealtimeFreshnessPolicy policy;
    private final AtomicReference<Instant> lastMissingHashWarn = new AtomicReference<>();

    @Autowired
    public RedisRealtimeSnapshotReader(StringRedisTemplate redisTemplate,
                                       @Value("${realtime.redis.read-mode:auto}") String mode,
                                       RealtimeFreshnessPolicy policy) {
        this(redisTemplate, RealtimeReadMode.parse(mode), policy);
    }

    /** Default 120/600 s policy on the system clock (tests). */
    RedisRealtimeSnapshotReader(StringRedisTemplate redisTemplate, String mode) {
        this(redisTemplate, RealtimeReadMode.parse(mode), RealtimeFreshnessPolicy.defaults());
    }

    /** Default 120/600 s policy on the given clock (tests). */
    RedisRealtimeSnapshotReader(StringRedisTemplate redisTemplate, RealtimeReadMode mode, Clock clock) {
        this(redisTemplate, mode, RealtimeFreshnessPolicy.defaults(clock));
    }

    RedisRealtimeSnapshotReader(StringRedisTemplate redisTemplate, RealtimeReadMode mode, RealtimeFreshnessPolicy policy) {
        this.redisTemplate = redisTemplate;
        this.mode = mode;
        this.policy = policy;
        log.info("Realtime Redis read mode: {}, max stale {}s", mode.name().toLowerCase(), policy.maxStaleSeconds());
    }

    RealtimeReadMode mode() { return mode; }

    /** Upper bound on the age of returned values ({@code realtime.freshness.max-stale-seconds}). */
    Duration maxAge() { return policy.maxStale(); }

    @Override
    public RealtimeSnapshot read(List<String> stationIds) {
        if (stationIds.isEmpty()) return RealtimeSnapshot.empty();
        return switch (mode) {
            case LEGACY -> findLegacy(stationIds);
            case VERSIONED -> {
                OptionalLong version = currentVersion();
                yield version.isPresent()
                        ? findVersioned(version.getAsLong(), stationIds, false).orElseGet(RealtimeSnapshot::empty)
                        : RealtimeSnapshot.empty();
            }
            case AUTO -> {
                OptionalLong version = currentVersion();
                if (version.isEmpty()) yield findLegacy(stationIds);
                yield findVersioned(version.getAsLong(), stationIds, true).orElseGet(() -> {
                    warnMissingVersionHash(version.getAsLong());
                    return findLegacy(stationIds);
                });
            }
        };
    }

    private void warnMissingVersionHash(long version) {
        Instant now = policy.now();
        Instant last = lastMissingHashWarn.get();
        if (last != null && now.isBefore(last.plus(MISSING_HASH_WARN_INTERVAL))) return;
        if (!lastMissingHashWarn.compareAndSet(last, now)) return;
        log.warn("Realtime pointer {} points to missing hash {}{}; falling back to legacy keys (warning throttled to once per {}s)",
                CURRENT_KEY, VERSION_KEY_PREFIX, version, MISSING_HASH_WARN_INTERVAL.toSeconds());
    }

    /** Pointer value, or empty when absent or not a non-negative integer (a corrupt pointer is logged). */
    OptionalLong currentVersion() {
        String raw = redisTemplate.opsForValue().get(CURRENT_KEY);
        if (raw == null || raw.isBlank()) return OptionalLong.empty();
        try {
            long version = Long.parseLong(raw.trim());
            if (version >= 0) return OptionalLong.of(version);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        log.warn("Ignoring malformed realtime version pointer {} (length {})", CURRENT_KEY, raw.length());
        return OptionalLong.empty();
    }

    /**
     * Reads the version hash. When {@code checkExists} is set and no requested id had a value, verifies the hash
     * exists with one EXISTS round trip and returns empty when it does not (the caller decides the fallback).
     */
    private Optional<RealtimeSnapshot> findVersioned(long version, List<String> stationIds, boolean checkExists) {
        String key = VERSION_KEY_PREFIX + version;
        List<Object> values = redisTemplate.opsForHash().multiGet(key, List.<Object>copyOf(stationIds));
        if (checkExists && (values == null || values.stream().allMatch(Objects::isNull))
                && !Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return Optional.empty();
        }
        Map<String, RealtimeStationStatus> result = new HashMap<>();
        Set<String> excluded = new HashSet<>();
        Instant now = policy.now();
        int rejected = 0;
        for (int i = 0; i < stationIds.size(); i++) {
            Object value = values == null || i >= values.size() ? null : values.get(i);
            if (value == null) continue;
            String id = stationIds.get(i);
            switch (RealtimeSnapshotValues.parseVersioned(id, value.toString())) {
                case RealtimeSnapshotValues.Valid valid when servable(valid.status().updatedAt(), now) -> result.put(id, valid.status());
                case RealtimeSnapshotValues.Excluded ignored -> excluded.add(id);
                default -> rejected++;
            }
        }
        if (rejected > 0 || !excluded.isEmpty()) {
            log.debug("Realtime version {}: {} entries rejected (corrupt or older than max-stale), {} excluded by gate",
                    version, rejected, excluded.size());
        }
        return Optional.of(new RealtimeSnapshot(result, excluded));
    }

    private RealtimeSnapshot findLegacy(List<String> stationIds) {
        List<Object> hashes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            stationIds.forEach(id -> connection.hashCommands().hGetAll(bytes(KEY_PREFIX + id)));
            return null;
        });
        Map<String, RealtimeStationStatus> result = new HashMap<>();
        Instant now = policy.now();
        for (int i = 0; i < stationIds.size() && i < hashes.size(); i++) {
            if (!(hashes.get(i) instanceof Map<?, ?> values) || values.isEmpty()) continue;
            String id = stationIds.get(i);
            // Corrupt entries are unavailable, never interpreted as zero bikes.
            RealtimeSnapshotValues.fromLegacyHash(id, values)
                    .filter(candidate -> servable(candidate.updatedAt(), now))
                    .ifPresent(status -> result.put(id, status));
        }
        // The legacy layout has no publication gate, so nothing is ever reported as excluded (D3 limitation).
        return new RealtimeSnapshot(result, Set.of());
    }

    /** FRESH or STALE under the configured policy; older than max-stale or too far in the future is dropped. */
    private boolean servable(Instant updatedAt, Instant now) {
        return policy.classify(updatedAt, now).status().servable();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
