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
    /** Every refresh re-reads at least this much before the last checkpoint (rows written slightly late). */
    static final Duration CHECKPOINT_OVERLAP = Duration.ofMinutes(10);
    /**
     * Rows can arrive much later than their observed slot (collector backlog or backfill after an outage). Once a day
     * is marked ready, reads are served from the preloaded store without a MySQL fallback, so such rows would never
     * appear. The table has no insert-time column to select by, so the recent window is re-copied periodically.
     */
    static final Duration LATE_ARRIVAL_WINDOW = Duration.ofHours(3);
    static final Duration LATE_RESCAN_INTERVAL = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final PreloadedInventoryStore store;
    private LocalDateTime lastLateRescan;

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
            var from = checkpoint == null ? through.toLocalDate().atStartOfDay() : checkpoint.minus(CHECKPOINT_OVERLAP);
            boolean lateRescan = lastLateRescan == null || !through.isBefore(lastLateRescan.plus(LATE_RESCAN_INTERVAL));
            if (lateRescan && from.isAfter(through.minus(LATE_ARRIVAL_WINDOW))) from = through.minus(LATE_ARRIVAL_WINDOW);
            if (from.isBefore(oldest.atStartOfDay())) from = oldest.atStartOfDay();
            if (from.isAfter(through)) from = through.minus(CHECKPOINT_OVERLAP);
            copy(from, through);
            // Checkpoint advances only after every Redis write succeeds. Replays are idempotent.
            store.completed(through);
            if (lateRescan) lastLateRescan = through;
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
