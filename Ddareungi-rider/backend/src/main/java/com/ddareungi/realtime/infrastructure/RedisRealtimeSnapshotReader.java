package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.application.RealtimeSnapshotReader;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

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
 * When the pointer exists but its hash has expired every id misses (unknown), as the spec requires;
 * auto does not fall back to legacy in that case because the pointer is the collector's source of truth.
 */
@Profile("infra")
@Component
public class RedisRealtimeSnapshotReader implements RealtimeSnapshotReader {
    static final String KEY_PREFIX = "station:realtime:";
    static final String CURRENT_KEY = "station:realtime:current";
    static final String VERSION_KEY_PREFIX = "station:realtime:v";
    /** Same freshness window the legacy reader always applied (legacy key TTL). FRESH/STALE (120/600s) is A3. */
    static final Duration MAX_AGE = Duration.ofMinutes(5);
    static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeSnapshotReader.class);

    private final StringRedisTemplate redisTemplate;
    private final RealtimeReadMode mode;
    private final Clock clock;

    @Autowired
    public RedisRealtimeSnapshotReader(StringRedisTemplate redisTemplate,
                                       @Value("${realtime.redis.read-mode:auto}") String mode) {
        this(redisTemplate, RealtimeReadMode.parse(mode), Clock.systemUTC());
    }

    RedisRealtimeSnapshotReader(StringRedisTemplate redisTemplate, RealtimeReadMode mode, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.mode = mode;
        this.clock = clock;
        log.info("Realtime Redis read mode: {}", mode.name().toLowerCase());
    }

    RealtimeReadMode mode() { return mode; }

    @Override
    public Map<String, RealtimeStationStatus> findAll(List<String> stationIds) {
        if (stationIds.isEmpty()) return Map.of();
        return switch (mode) {
            case LEGACY -> findLegacy(stationIds);
            case VERSIONED -> {
                OptionalLong version = currentVersion();
                yield version.isPresent() ? findVersioned(version.getAsLong(), stationIds) : Map.of();
            }
            case AUTO -> {
                OptionalLong version = currentVersion();
                yield version.isPresent() ? findVersioned(version.getAsLong(), stationIds) : findLegacy(stationIds);
            }
        };
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

    private Map<String, RealtimeStationStatus> findVersioned(long version, List<String> stationIds) {
        List<Object> values = redisTemplate.opsForHash().multiGet(VERSION_KEY_PREFIX + version, List.<Object>copyOf(stationIds));
        Map<String, RealtimeStationStatus> result = new HashMap<>();
        Instant now = clock.instant();
        int rejected = 0;
        for (int i = 0; i < stationIds.size(); i++) {
            Object value = values == null || i >= values.size() ? null : values.get(i);
            if (value == null) continue;
            String id = stationIds.get(i);
            Optional<RealtimeStationStatus> status = RealtimeSnapshotValues.fromVersionedJson(id, value.toString())
                    .filter(candidate -> fresh(candidate.updatedAt(), now));
            if (status.isPresent()) result.put(id, status.get());
            else rejected++;
        }
        if (rejected > 0) log.debug("Realtime version {}: {} entries rejected (corrupt, hidden or not fresh)", version, rejected);
        return result;
    }

    private Map<String, RealtimeStationStatus> findLegacy(List<String> stationIds) {
        List<Object> hashes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            stationIds.forEach(id -> connection.hashCommands().hGetAll(bytes(KEY_PREFIX + id)));
            return null;
        });
        Map<String, RealtimeStationStatus> result = new HashMap<>();
        Instant now = clock.instant();
        for (int i = 0; i < stationIds.size() && i < hashes.size(); i++) {
            if (!(hashes.get(i) instanceof Map<?, ?> values) || values.isEmpty()) continue;
            String id = stationIds.get(i);
            // Corrupt entries are unavailable, never interpreted as zero bikes.
            RealtimeSnapshotValues.fromLegacyHash(id, values)
                    .filter(candidate -> fresh(candidate.updatedAt(), now))
                    .ifPresent(status -> result.put(id, status));
        }
        return result;
    }

    private static boolean fresh(Instant updatedAt, Instant now) {
        return !updatedAt.isBefore(now.minus(MAX_AGE)) && !updatedAt.isAfter(now.plus(MAX_CLOCK_SKEW));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
