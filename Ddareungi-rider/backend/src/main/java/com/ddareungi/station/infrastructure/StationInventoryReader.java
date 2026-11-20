package com.ddareungi.station.infrastructure;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("infra")
public class StationInventoryReader {
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private final JdbcTemplate jdbc;
    private final InventoryDayCache cache;

    public StationInventoryReader(JdbcTemplate jdbc, InventoryDayCache cache) { this.jdbc = jdbc; this.cache = cache; }

    public record Observation(String stationId, OffsetDateTime observedSlot, int availableBikes) {}

    public List<Observation> read(String stationId, OffsetDateTime startTime) {
        return read(stationId, startTime, false);
    }

    public List<Observation> read(String stationId, OffsetDateTime startTime, boolean includePrevious) {
        if (stationId == null || stationId.isBlank() || stationId.length() > 30 || startTime == null)
            throw new IllegalArgumentException("대여소와 기준 시각을 확인하세요.");
        LocalDateTime start = startTime.withOffsetSameInstant(KST).toLocalDateTime();
        if (start.getMinute() % 5 != 0 || start.getSecond() != 0 || start.getNano() != 0)
            throw new IllegalArgumentException("기준 시각은 5분 단위여야 합니다.");
        var result = new ArrayList<Observation>();
        var loaded = new java.util.HashMap<java.time.LocalDate, List<Observation>>();
        for (int days : new int[] {0, 1, 7}) {
            var reference = start.minusDays(days);
            var from = includePrevious ? reference.minusMinutes(10) : reference;
            var until = reference.plusMinutes(15);
            for (var date = from.toLocalDate(); !date.isAfter(until.toLocalDate()); date = date.plusDays(1)) {
                var records = loaded.computeIfAbsent(date, day -> cache.get(stationId, day, () -> readDay(stationId, day)));
                records.stream().filter(row -> !row.observedSlot().toLocalDateTime().isBefore(from)
                        && !row.observedSlot().toLocalDateTime().isAfter(until)).forEach(result::add);
            }
        }
        return result;
    }

    private List<Observation> readDay(String stationId, java.time.LocalDate day) {
        return jdbc.query("""
                SELECT station_id, observed_slot, available_bikes FROM station_inventory_5m
                WHERE station_id = ? AND observed_slot >= ? AND observed_slot < ?
                ORDER BY observed_slot LIMIT 288
                """, (rs, row) -> new Observation(rs.getString("station_id"),
                rs.getObject("observed_slot", LocalDateTime.class).atOffset(KST), rs.getInt("available_bikes")),
                stationId, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
    }
}
