package com.ddareungi.station.infrastructure;

import java.time.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("infra")
public class InventoryPreloader {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InventoryPreloader.class);
    private final JdbcTemplate jdbc;
    private final PreloadedInventoryStore store;
    public InventoryPreloader(JdbcTemplate jdbc, PreloadedInventoryStore store) { this.jdbc = jdbc; this.store = store; }

    @Scheduled(scheduler = "inventoryPreloadScheduler", initialDelay = 1000, fixedDelay = 30000)
    public synchronized void refresh() {
        try {
            var through = LocalDateTime.now(PreloadedInventoryStore.KST).withNano(0);
            var oldest = through.toLocalDate().minusDays(7);
            for (var day = oldest; !day.isAfter(through.toLocalDate()); day = day.plusDays(1)) {
                if (!store.ready(day)) {
                    var end = day.plusDays(1).atStartOfDay();
                    long rows = copy(day.atStartOfDay(), end.isAfter(through) ? through : end);
                    store.markReady(day);
                    log.info("Inventory preload completed: date={}, rows={}", day, rows);
                }
            }
            var checkpoint = store.checkpoint();
            var from = checkpoint == null ? through.toLocalDate().atStartOfDay() : checkpoint.minusMinutes(10);
            if (from.isBefore(oldest.atStartOfDay())) from = oldest.atStartOfDay();
            if (from.isAfter(through)) from = through.minusMinutes(10);
            copy(from, through);
            // Checkpoint advances only after every Redis write succeeds. Replays are idempotent.
            store.completed(through);
        } catch (RuntimeException error) {
            log.warn("Inventory preload failed; database fallback available: {}", error.getClass().getSimpleName());
        }
    }

    long copy(LocalDateTime from, LocalDateTime until) {
        long count = 0;
        // At most one hour of all-station observations in memory at a time.
        while (from.isBefore(until)) {
            var end = from.plusHours(1).isAfter(until) ? until : from.plusHours(1);
            var rows = jdbc.query("""
                SELECT station_id, observed_slot, available_bikes FROM station_inventory_5m
                WHERE observed_slot >= ? AND observed_slot < ? ORDER BY observed_slot, station_id
                """, (rs, index) -> new StationInventoryReader.Observation(rs.getString("station_id"),
                    rs.getObject("observed_slot", LocalDateTime.class).atOffset(PreloadedInventoryStore.KST), rs.getInt("available_bikes")), from, end);
            store.save(rows);
            count += rows.size();
            from = end;
        }
        return count;
    }
}
