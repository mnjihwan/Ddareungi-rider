package com.ddareungi.station.infrastructure;

import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;

@Component
@Profile("infra")
public class InventoryDayCache {
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    static final Duration TODAY_TTL = Duration.ofSeconds(30);
    /** Yesterday can still receive late (backfilled) observations, so it is cached briefly. */
    static final Duration YESTERDAY_TTL = Duration.ofMinutes(10);
    static final Duration HISTORY_TTL = Duration.ofHours(6);
    private final StringRedisTemplate redis;
    private final PreloadedInventoryStore preloaded;
    /**
     * Striped locks that coalesce concurrent misses of one key on this instance. ReentrantLock, not synchronized:
     * the loader blocks on Redis and MySQL, and on JDK 21 a virtual thread blocked inside a synchronized block (or
     * waiting to enter one) pins its carrier thread, which can stall every request on a small instance.
     */
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public InventoryDayCache(StringRedisTemplate redis, PreloadedInventoryStore preloaded) {
        this.redis = redis;
        this.preloaded = preloaded;
        Arrays.setAll(locks, index -> new ReentrantLock());
    }

    public List<StationInventoryReader.Observation> get(String station, LocalDate date,
            Supplier<List<StationInventoryReader.Observation>> loader) {
        LocalDate today = LocalDate.now(KST);
        if (date.isAfter(today)) return List.of();
        try {
            var ready = preloaded.read(station, date);
            if (ready.isPresent()) return ready.get();
        } catch (DataAccessException ignored) { /* Use the existing cache-aside fallback. */ }
        // Bound cache population to the UI's rolling comparison period.
        if (date.isBefore(today.minusDays(8))) return loader.get();
        String key = "station:inventory:day:v1:" + station + ":" + date;
        // Coalesce concurrent misses for this application instance without an unbounded lock map.
        ReentrantLock lock = locks[Math.floorMod(key.hashCode(), locks.length)];
        lock.lock();
        try {
            try {
                String value = redis.opsForValue().get(key);
                if (value != null) {
                    try { return decode(station, date, value); }
                    catch (IllegalArgumentException ignored) { /* Reload corrupt cache from source. */ }
                }
            } catch (DataAccessException ignored) { /* Redis is optional; MySQL remains authoritative. */ }
            var rows = loader.get();
            try { redis.opsForValue().set(key, encode(rows), ttl(date, today, rows.isEmpty())); }
            catch (DataAccessException ignored) { /* Do not turn a successful DB read into a failure. */ }
            return rows;
        } finally {
            lock.unlock();
        }
    }

    static Duration ttl(LocalDate date, LocalDate today, boolean empty) {
        if (date.equals(today) || empty) return TODAY_TTL;
        if (date.equals(today.minusDays(1))) return YESTERDAY_TTL;
        return HISTORY_TTL;
    }

    private String encode(List<StationInventoryReader.Observation> rows) {
        var value = new StringBuilder("v1");
        for (var row : rows) {
            var time = row.observedSlot().withOffsetSameInstant(KST);
            value.append(';').append(time.getHour() * 60 + time.getMinute()).append(':').append(row.availableBikes());
        }
        return value.toString();
    }

    private List<StationInventoryReader.Observation> decode(String station, LocalDate date, String value) {
        String[] entries = value.split(";", -1);
        if (!entries[0].equals("v1") || entries.length > 289) throw new IllegalArgumentException();
        var result = new ArrayList<StationInventoryReader.Observation>();
        var slots = new HashSet<Integer>();
        for (int i = 1; i < entries.length; i++) {
            String[] parts = entries[i].split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            int minute = Integer.parseInt(parts[0]), count = Integer.parseInt(parts[1]);
            if (minute < 0 || minute >= 1440 || minute % 5 != 0 || count < 0 || !slots.add(minute))
                throw new IllegalArgumentException();
            result.add(new StationInventoryReader.Observation(station, date.atStartOfDay().plusMinutes(minute).atOffset(KST), count));
        }
        return result;
    }
}
