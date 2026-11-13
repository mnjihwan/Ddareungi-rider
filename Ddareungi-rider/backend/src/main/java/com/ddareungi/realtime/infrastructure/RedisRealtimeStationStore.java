package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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

    public RedisRealtimeStationStore(StringRedisTemplate redisTemplate) { this.redisTemplate = redisTemplate; }

    public void save(RealtimeStationStatus status) {
        saveAll(List.of(status));
    }

    /**
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

    public Map<String, RealtimeStationStatus> findAll(List<String> stationIds) {
        if (stationIds.isEmpty()) return Map.of();
        List<Object> hashes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            stationIds.forEach(id -> connection.hashCommands().hGetAll(bytes(KEY_PREFIX + id)));
            return null;
        });
        Map<String, RealtimeStationStatus> result = new HashMap<>();
        Instant now = Instant.now();
        for (int i = 0; i < stationIds.size(); i++) {
            if (!(hashes.get(i) instanceof Map<?, ?> values) || values.isEmpty()) continue;
            try {
                Instant updatedAt = Instant.parse(values.get("updatedAt").toString());
                if (updatedAt.isBefore(now.minus(TTL)) || updatedAt.isAfter(now.plusSeconds(30))) continue;
                int count = Integer.parseInt(values.get("availableBikeCount").toString());
                if (count < 0) continue;
                result.put(stationIds.get(i), new RealtimeStationStatus(stationIds.get(i), values.get("stationName").toString(),
                        Integer.parseInt(values.get("rackTotalCount").toString()), count,
                        Double.parseDouble(values.get("sharedRate").toString()),
                        Double.parseDouble(values.get("latitude").toString()), Double.parseDouble(values.get("longitude").toString()), updatedAt));
            } catch (RuntimeException ignored) {
                // Corrupt entries are unavailable, never interpreted as zero bikes.
            }
        }
        return result;
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
