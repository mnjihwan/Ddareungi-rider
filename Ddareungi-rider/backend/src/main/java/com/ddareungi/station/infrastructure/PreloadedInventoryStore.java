package com.ddareungi.station.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Eight calendar days of observed inventory; MySQL is still the durable source. */
@Component
@Profile("infra")
public class PreloadedInventoryStore {
    static final ZoneOffset KST = ZoneOffset.ofHours(9);
    static final String PREFIX = "station:inventory:preloaded:v1:";
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<List> READ = new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1])==0 or redis.call('EXISTS',KEYS[2])==0 or redis.call('EXISTS',KEYS[4])==0 then return {'miss'} end
        local values=redis.call('HGETALL',KEYS[3])
        if #values==0 and redis.call('SISMEMBER',KEYS[4],ARGV[1])==1 then return {'miss'} end
        table.insert(values,1,'hit'); return values
        """, List.class);
    private static final byte[] UPSERT = ("redis.call('HSET',KEYS[1],unpack(ARGV,3)); "
        + "redis.call('EXPIREAT',KEYS[1],ARGV[1]); redis.call('SADD',KEYS[2],ARGV[2]); "
        + "redis.call('EXPIREAT',KEYS[2],ARGV[1]); return 1").getBytes(StandardCharsets.UTF_8);

    public PreloadedInventoryStore(StringRedisTemplate redis) { this.redis = redis; }
    static String dayPrefix(LocalDate day) { return PREFIX + day + ":"; }
    static Instant expires(LocalDate day) { return day.plusDays(8).atStartOfDay().toInstant(KST); }
    public boolean ready(LocalDate day) {
        return Boolean.TRUE.equals(redis.hasKey(dayPrefix(day) + "ready"))
            && Boolean.TRUE.equals(redis.hasKey(dayPrefix(day) + "stations"));
    }
    public void markReady(LocalDate day) {
        redis.opsForSet().add(dayPrefix(day) + "stations", "__ready__");
        redis.expireAt(dayPrefix(day) + "stations", expires(day));
        redis.opsForValue().set(dayPrefix(day) + "ready", "1", Duration.between(Instant.now(), expires(day)));
    }
    public LocalDateTime checkpoint() {
        String value = redis.opsForValue().get(PREFIX + "checkpoint");
        return value == null ? null : LocalDateTime.parse(value);
    }
    public void completed(LocalDateTime through) {
        redis.opsForValue().set(PREFIX + "checkpoint", through.toString(), Duration.ofDays(9));
        redis.opsForValue().set(PREFIX + "heartbeat", "1", Duration.ofMinutes(2));
    }

    public void save(List<StationInventoryReader.Observation> rows) {
        var grouped = new LinkedHashMap<String, List<StationInventoryReader.Observation>>();
        for (var row : rows) {
            var time = row.observedSlot().withOffsetSameInstant(KST);
            if (row.availableBikes() < 0 || time.getMinute() % 5 != 0 || time.getSecond() != 0)
                throw new IllegalArgumentException("Invalid inventory observation");
            grouped.computeIfAbsent(dayPrefix(time.toLocalDate()) + row.stationId(), key -> new ArrayList<>()).add(row);
        }
        var commands = new ArrayList<byte[][]>();
        grouped.forEach((key, values) -> {
            var date = values.getFirst().observedSlot().withOffsetSameInstant(KST).toLocalDate();
            var args = new ArrayList<String>();
            args.add(key); args.add(dayPrefix(date) + "stations");
            args.add(Long.toString(expires(date).getEpochSecond())); args.add(values.getFirst().stationId());
            for (var row : values) {
                var time = row.observedSlot().withOffsetSameInstant(KST);
                args.add(Integer.toString(time.getHour() * 60 + time.getMinute()));
                args.add(Integer.toString(row.availableBikes()));
            }
            commands.add(args.stream().map(v -> v.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new));
        });
        for (int start = 0; start < commands.size(); start += 500) {
            var batch = commands.subList(start, Math.min(start + 500, commands.size()));
            redis.executePipelined((RedisCallback<Object>) connection -> {
                batch.forEach(args -> connection.scriptingCommands().eval(UPSERT, ReturnType.INTEGER, 2, args));
                return null;
            });
        }
    }

    public Optional<List<StationInventoryReader.Observation>> read(String station, LocalDate day) {
        var today = LocalDate.now(KST);
        if (day.isBefore(today.minusDays(7)) || day.isAfter(today)) return Optional.empty();
        // One atomic Redis round trip checks readiness and distinguishes true absence from eviction.
        List<?> values = redis.execute(READ, List.of(dayPrefix(day) + "ready", PREFIX + "heartbeat",
            dayPrefix(day) + station, dayPrefix(day) + "stations"), station);
        if (values == null || values.isEmpty() || !"hit".equals(values.getFirst())) return Optional.empty();
        try {
            if (values.size() > 577 || values.size() % 2 != 1) return Optional.empty();
            var result = new ArrayList<StationInventoryReader.Observation>();
            for (int i = 1; i < values.size(); i += 2) {
                int minute = Integer.parseInt(values.get(i).toString());
                int count = Integer.parseInt(values.get(i + 1).toString());
                if (minute < 0 || minute >= 1440 || minute % 5 != 0 || count < 0) return Optional.empty();
                result.add(new StationInventoryReader.Observation(station, day.atStartOfDay().plusMinutes(minute).atOffset(KST), count));
            }
            result.sort(Comparator.comparing(StationInventoryReader.Observation::observedSlot));
            return Optional.of(result);
        } catch (IllegalArgumentException error) { return Optional.empty(); }
    }
}
