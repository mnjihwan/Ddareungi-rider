package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.application.CachedRealtimeSnapshot;
import com.ddareungi.realtime.application.RealtimeSnapshot;
import com.ddareungi.realtime.application.RealtimeSnapshotSource;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory realtime snapshot kept current by a background poller (R3, D5). Request threads only call
 * {@link #current(Instant)}, a volatile read: zero Redis round trips on the request path.
 *
 * <p>{@link #poll(Collection)} (every {@code realtime.snapshot.poll-interval}, 5 s by default) reads
 * {@code station:realtime:current} and {@code station:realtime:last-success-at} with one MGET and then:
 * <ul>
 *   <li>versioned: {@code HGETALL station:realtime:v{n}} only when the pointer differs from the loaded version.</li>
 *   <li>legacy: pipelined {@code HGETALL station:realtime:{id}} for the catalog ids, only when
 *       {@code last-success-at} changed, the id set changed, or {@code realtime.snapshot.legacy-safety-reload}
 *       (60 s) has passed since the last legacy load. TTLs are never used for invalidation.</li>
 *   <li>auto: versioned when the pointer exists and its hash is non-empty; legacy when there is no pointer or the
 *       pointer names a missing/empty hash (R2 meaning kept; the warning is throttled).</li>
 * </ul>
 * Any Redis failure keeps the previous snapshot (and the cached {@code last-success-at}); its values then age from
 * STALE into UNAVAILABLE under {@link RealtimeFreshnessPolicy} because freshness is always judged from the original
 * collection time at request time. Values are stored regardless of age, parsed with the same rules as
 * {@link RedisRealtimeSnapshotReader} (gate D3 for versioned values; legacy values have no gate).
 * Each instance polls on its own (no shared state, instances differ by at most about one poll interval).
 */
@Profile("infra")
@Component
public class RealtimeSnapshotCache implements RealtimeSnapshotSource {
    private static final Logger log = LoggerFactory.getLogger(RealtimeSnapshotCache.class);
    private static final int PIPELINE_CHUNK_SIZE = 500;

    private enum Source { NONE, VERSIONED, LEGACY, EMPTY }

    private final StringRedisTemplate redis;
    private final RealtimeReadMode mode;
    private final RealtimeFreshnessPolicy policy;
    private final RealtimeSnapshotCacheProperties properties;
    private final ReentrantLock pollLock = new ReentrantLock();

    private volatile CachedRealtimeSnapshot current = CachedRealtimeSnapshot.empty();
    // Poller-only state (guarded by pollLock).
    private Source source = Source.NONE;
    private long loadedVersion = -1;
    private @Nullable Instant legacyLastSuccess;
    private @Nullable Instant legacyLoadedAt;
    private @Nullable Collection<String> legacyIds;
    private int consecutiveFailures;
    private @Nullable Instant lastMissingHashWarn;

    @Autowired
    public RealtimeSnapshotCache(StringRedisTemplate redis, @Value("${realtime.redis.read-mode:auto}") String mode,
                                 RealtimeFreshnessPolicy policy, RealtimeSnapshotCacheProperties properties) {
        this(redis, RealtimeReadMode.parse(mode), policy, properties);
    }

    public RealtimeSnapshotCache(StringRedisTemplate redis, RealtimeReadMode mode, RealtimeFreshnessPolicy policy,
                                 RealtimeSnapshotCacheProperties properties) {
        this.redis = redis;
        this.mode = mode;
        this.policy = policy;
        this.properties = properties;
    }

    public RealtimeSnapshotCacheProperties properties() { return properties; }

    /** Memory only; never touches Redis. */
    @Override
    public CachedRealtimeSnapshot current(Instant now) {
        return current;
    }

    /**
     * One poll. {@code legacyIds} are the catalog station ids (the legacy layout has one key per station); pass the
     * same collection instance while the catalog is unchanged so the legacy layout is not reloaded needlessly.
     *
     * @return true when the snapshot was replaced from storage
     */
    public boolean poll(Collection<String> legacyIds) {
        pollLock.lock();
        try {
            Instant now = policy.now();
            List<String> pointers = redis.opsForValue().multiGet(List.of(
                    RedisRealtimeSnapshotReader.CURRENT_KEY, RedisRealtimeStationStore.LAST_SUCCESS_KEY));
            OptionalLong version = parseVersion(pointers == null || pointers.isEmpty() ? null : pointers.get(0));
            Instant lastSuccess = parseInstant(pointers == null || pointers.size() < 2 ? null : pointers.get(1));
            boolean reloaded = switch (mode) {
                case LEGACY -> pollLegacy(legacyIds, lastSuccess, now);
                case VERSIONED -> {
                    if (version.isEmpty()) yield replaceWithEmpty(now);
                    Boolean loaded = pollVersioned(version.getAsLong(), now);
                    yield loaded == null ? replaceWithEmpty(now) : loaded;
                }
                case AUTO -> {
                    if (version.isEmpty()) yield pollLegacy(legacyIds, lastSuccess, now);
                    Boolean loaded = pollVersioned(version.getAsLong(), now);
                    if (loaded != null) yield loaded;
                    warnMissingHash(version.getAsLong(), now);
                    yield pollLegacy(legacyIds, lastSuccess, now);
                }
            };
            CachedRealtimeSnapshot snapshot = current;
            if (!Objects.equals(snapshot.lastSuccessAt(), lastSuccess)) {
                current = new CachedRealtimeSnapshot(snapshot.snapshot(), lastSuccess, snapshot.loadedAt());
            }
            if (consecutiveFailures > 0) log.info("Realtime snapshot poll recovered after {} failures", consecutiveFailures);
            consecutiveFailures = 0;
            return reloaded;
        } catch (RuntimeException exception) {
            // Keep the previous snapshot and last-success-at; values age into STALE/UNAVAILABLE by themselves.
            consecutiveFailures++;
            if (consecutiveFailures == 1 || consecutiveFailures % 60 == 0) {
                log.warn("Realtime snapshot poll failed {} time(s) in a row ({}); serving the previous snapshot",
                        consecutiveFailures, exception.getClass().getSimpleName());
            }
            return false;
        } finally {
            pollLock.unlock();
        }
    }

    /** @return true reloaded, false unchanged, null when the version hash is missing or empty */
    private @Nullable Boolean pollVersioned(long version, Instant now) {
        if (source == Source.VERSIONED && loadedVersion == version) return false;
        Map<Object, Object> entries = redis.opsForHash().entries(RedisRealtimeSnapshotReader.VERSION_KEY_PREFIX + version);
        if (entries == null || entries.isEmpty()) return null;
        Map<String, RealtimeStationStatus> statuses = new HashMap<>();
        Set<String> excluded = new HashSet<>();
        int rejected = 0;
        for (var entry : entries.entrySet()) {
            String id = String.valueOf(entry.getKey());
            switch (RealtimeSnapshotValues.parseVersioned(id, entry.getValue() == null ? null : entry.getValue().toString())) {
                case RealtimeSnapshotValues.Valid valid -> statuses.put(id, valid.status());
                case RealtimeSnapshotValues.Excluded ignored -> excluded.add(id);
                case RealtimeSnapshotValues.Corrupt ignored -> rejected++;
            }
        }
        publish(new RealtimeSnapshot(statuses, excluded), now);
        source = Source.VERSIONED;
        loadedVersion = version;
        log.debug("Realtime snapshot v{} loaded: {} public, {} excluded, {} corrupt", version, statuses.size(), excluded.size(), rejected);
        return true;
    }

    private boolean pollLegacy(Collection<String> ids, @Nullable Instant lastSuccess, Instant now) {
        boolean due = source != Source.LEGACY || ids != legacyIds || !Objects.equals(lastSuccess, legacyLastSuccess)
                || legacyLoadedAt == null || !now.isBefore(legacyLoadedAt.plus(properties.legacySafetyReload()));
        if (!due) return false;
        List<String> list = List.copyOf(ids);
        Map<String, RealtimeStationStatus> statuses = new HashMap<>();
        for (int offset = 0; offset < list.size(); offset += PIPELINE_CHUNK_SIZE) {
            List<String> chunk = list.subList(offset, Math.min(list.size(), offset + PIPELINE_CHUNK_SIZE));
            List<Object> hashes = redis.executePipelined((RedisCallback<Object>) connection -> {
                chunk.forEach(id -> connection.hashCommands().hGetAll(bytes(RedisRealtimeSnapshotReader.KEY_PREFIX + id)));
                return null;
            });
            for (int i = 0; i < chunk.size() && hashes != null && i < hashes.size(); i++) {
                if (!(hashes.get(i) instanceof Map<?, ?> values) || values.isEmpty()) continue;
                String id = chunk.get(i);
                RealtimeSnapshotValues.fromLegacyHash(id, values).ifPresent(status -> statuses.put(id, status));
            }
        }
        // The legacy layout has no publication gate, so nothing is ever excluded (D3 limitation).
        publish(new RealtimeSnapshot(statuses, Set.of()), now);
        source = Source.LEGACY;
        legacyIds = ids;
        legacyLastSuccess = lastSuccess;
        legacyLoadedAt = now;
        return true;
    }

    private boolean replaceWithEmpty(Instant now) {
        if (source == Source.EMPTY) return false;
        publish(RealtimeSnapshot.empty(), now);
        source = Source.EMPTY;
        return true;
    }

    private void publish(RealtimeSnapshot snapshot, Instant now) {
        // Callers update source/loadedVersion/legacy markers; a source switch always forces the next reload.
        current = new CachedRealtimeSnapshot(snapshot, current.lastSuccessAt(), now);
    }

    private void warnMissingHash(long version, Instant now) {
        if (lastMissingHashWarn != null && now.isBefore(lastMissingHashWarn.plus(RedisRealtimeSnapshotReader.MISSING_HASH_WARN_INTERVAL))) return;
        lastMissingHashWarn = now;
        log.warn("Realtime pointer {} points to missing hash {}{}; falling back to legacy keys (warning throttled to once per {}s)",
                RedisRealtimeSnapshotReader.CURRENT_KEY, RedisRealtimeSnapshotReader.VERSION_KEY_PREFIX, version,
                RedisRealtimeSnapshotReader.MISSING_HASH_WARN_INTERVAL.toSeconds());
    }

    private static OptionalLong parseVersion(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return OptionalLong.empty();
        try {
            long version = Long.parseLong(raw.trim());
            if (version >= 0) return OptionalLong.of(version);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return OptionalLong.empty();
    }

    private static @Nullable Instant parseInstant(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value.trim()); }
        catch (RuntimeException ignored) { return null; }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
