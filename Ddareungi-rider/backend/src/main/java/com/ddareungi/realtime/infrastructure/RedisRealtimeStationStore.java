package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

@Profile("infra")
@Repository
public class RedisRealtimeStationStore {
    private static final String KEY_PREFIX = "station:realtime:";
    private static final Duration TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redisTemplate;
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], unpack(ARGV, 2)); redis.call('EXPIRE', KEYS[1], ARGV[1]); return 1", Long.class);

    public RedisRealtimeStationStore(StringRedisTemplate redisTemplate) { this.redisTemplate = redisTemplate; }

    public void save(RealtimeStationStatus status) {
        String key = KEY_PREFIX + status.stationId();
        Map<String, String> values = Map.of(
                "stationName", status.stationName(),
                "rackTotalCount", Integer.toString(status.rackTotalCount()),
                "availableBikeCount", Integer.toString(status.availableBikeCount()),
                "sharedRate", Double.toString(status.sharedRate()),
                "latitude", Double.toString(status.latitude()),
                "longitude", Double.toString(status.longitude()),
                "updatedAt", status.updatedAt().toString());
        List<String> arguments = new java.util.ArrayList<>();
        arguments.add(Long.toString(TTL.toSeconds()));
        values.forEach((field, value) -> { arguments.add(field); arguments.add(value); });
        redisTemplate.execute(SAVE, List.of(key), arguments.toArray());
    }

    public Map<String, RealtimeStationStatus> findAll(List<String> stationIds) {
        if (stationIds.isEmpty()) return Map.of();
        List<Object> hashes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            stationIds.forEach(id -> connection.hashCommands().hGetAll((KEY_PREFIX + id).getBytes(StandardCharsets.UTF_8)));
            return null;
        });
        Map<String, RealtimeStationStatus> result = new HashMap<>();
        for (int i = 0; i < stationIds.size(); i++) {
            if (!(hashes.get(i) instanceof Map<?, ?> values) || values.isEmpty()) continue;
            try {
                Instant updatedAt = Instant.parse(values.get("updatedAt").toString());
                if (updatedAt.isBefore(Instant.now().minus(TTL)) || updatedAt.isAfter(Instant.now().plusSeconds(30))) continue;
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
}
