package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.application.RealtimeSnapshotReader;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Profile("infra")
@Repository
public class RedisRealtimeStationStore {
    private static final String KEY_PREFIX = "station:realtime:";
    private static final String LAST_SUCCESS_KEY = "station:realtime:last-success-at";
    private static final String LAST_ATTEMPT_KEY = "station:realtime:last-attempt-at";
    private static final Duration TTL = Duration.ofMinutes(5);
    // Bounds the size of one pipelined batch so a full refresh never builds one huge buffer.
    private static final int PIPELINE_CHUNK_SIZE = 500;
    private final StringRedisTemplate redisTemplate;
    private final RealtimeSnapshotReader reader;

    public RedisRealtimeStationStore(StringRedisTemplate redisTemplate, RealtimeSnapshotReader reader) {
        this.redisTemplate = redisTemplate;
        this.reader = reader;
    }

    public void save(RealtimeStationStatus status) {
        saveAll(List.of(status));
    }

    /**
     * Legacy write path (one hash per station), unchanged until the collector owns Redis writes (roadmap R1/R2).
     * Writes all statuses with pipelined HSET + EXPIRE instead of one round trip per station.
     * Readers already reject entries whose updatedAt is outside the TTL window, so a
     * partially applied pipeline can never surface stale counts as fresh.
     */
    public void saveAll(List<RealtimeStationStatus> statuses) {
        if (statuses.isEmpty()) return;
        long ttlSeconds = TTL.toSeconds();
        for (int offset = 0; offset < statuses.size(); offset += PIPELINE_CHUNK_SIZE) {
            var chunk = statuses.subList(offset, Math.min(statuses.size(), offset + PIPELINE_CHUNK_SIZE));
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (var status : chunk) {
                    byte[] key = bytes(KEY_PREFIX + status.stationId());
                    connection.hashCommands().hMSet(key, fields(status));
                    connection.keyCommands().expire(key, ttlSeconds);
                }
                return null;
            });
        }
    }

    public void markAttempt(Instant at) {
        redisTemplate.opsForValue().set(LAST_ATTEMPT_KEY, at.toString());
    }

    public void markSuccess(Instant at) {
        redisTemplate.opsForValue().set(LAST_SUCCESS_KEY, at.toString());
    }

    public Optional<Instant> lastSuccessAt() {
        return parseInstant(redisTemplate.opsForValue().get(LAST_SUCCESS_KEY));
    }

    public Optional<Instant> lastAttemptAt() {
        return parseInstant(redisTemplate.opsForValue().get(LAST_ATTEMPT_KEY));
    }

    /**
     * Reads through {@link RealtimeSnapshotReader} (versioned or legacy layout per {@code realtime.redis.read-mode}).
     * Kept here so existing callers ({@code JdbcStationSearchAdapter}) keep the same signature.
     */
    public Map<String, RealtimeStationStatus> findAll(List<String> stationIds) {
        return reader.findAll(stationIds);
    }

    private static Map<byte[], byte[]> fields(RealtimeStationStatus status) {
        var values = new LinkedHashMap<byte[], byte[]>();
        values.put(bytes("stationName"), bytes(status.stationName()));
        values.put(bytes("rackTotalCount"), bytes(Integer.toString(status.rackTotalCount())));
        values.put(bytes("availableBikeCount"), bytes(Integer.toString(status.availableBikeCount())));
        values.put(bytes("sharedRate"), bytes(Double.toString(status.sharedRate())));
        values.put(bytes("latitude"), bytes(Double.toString(status.latitude())));
        values.put(bytes("longitude"), bytes(Double.toString(status.longitude())));
        values.put(bytes("updatedAt"), bytes(status.updatedAt().toString()));
        return values;
    }

    private static Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try { return Optional.of(Instant.parse(value)); }
        catch (RuntimeException ignored) { return Optional.empty(); }
    }

    private static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }
}
