package com.ddareungi.station.infrastructure;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StationInventoryReaderTest {
    private InventoryDayCache passthroughCache() {
        var cache = mock(InventoryDayCache.class);
        when(cache.get(anyString(), any(), any())).thenAnswer(call -> {
            java.util.function.Supplier<?> loader = call.getArgument(2);
            return loader.get();
        });
        return cache;
    }
    @Test void queriesOnlySelectedStationAndThreeShortKoreaTimeRanges() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var rs = mock(ResultSet.class);
        when(rs.getString("station_id")).thenReturn("ST-10");
        when(rs.getInt("available_bikes")).thenReturn(0);
        when(rs.getObject("observed_slot", LocalDateTime.class)).thenReturn(LocalDateTime.parse("2026-10-28T21:55:00"));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StationInventoryReader.Observation>>any(), any(Object[].class)))
            .thenAnswer(invocation -> {
                RowMapper<StationInventoryReader.Observation> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(rs, 0));
            });
        var result = new StationInventoryReader(jdbc, passthroughCache()).read("ST-10", OffsetDateTime.parse("2026-10-28T12:55:00Z"));
        assertThat(result.getFirst().observedSlot().toString()).isEqualTo("2026-10-28T21:55+09:00");
        assertThat(result.getFirst().availableBikes()).isZero();
        for (int days : new int[] {0, 1, 7}) {
            var start = LocalDateTime.parse("2026-10-28T21:55:00").minusDays(days);
            verify(jdbc).query(contains("station_id = ?"), org.mockito.ArgumentMatchers.<RowMapper<StationInventoryReader.Observation>>any(),
                eq("ST-10"), eq(start.toLocalDate().atStartOfDay()), eq(start.toLocalDate().plusDays(1).atStartOfDay()));
        }
    }

    @Test void rejectsUnalignedTimeBeforeQueryingAndLeavesMissingDataEmpty() {
        var jdbc = mock(JdbcTemplate.class);
        var reader = new StationInventoryReader(jdbc, passthroughCache());
        assertThatThrownBy(() -> reader.read("ST-1", OffsetDateTime.parse("2026-10-28T21:56:00+09:00")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
        assertThat(reader.read("ST-1", OffsetDateTime.parse("2026-10-28T23:55:00+09:00"))).isEmpty();
    }

    @Test void optionallyIncludesBothPrecedingSlotsAcrossMidnight() {
        var reference = OffsetDateTime.parse("2026-10-28T00:05:00+09:00");
        var records = List.of(-15, -10, -5, 0, 5, 10, 15, 20).stream()
            .map(minutes -> new StationInventoryReader.Observation("ST-10", reference.plusMinutes(minutes), 0))
            .toList();
        var cache = mock(InventoryDayCache.class);
        when(cache.get(eq("ST-10"), any(), any())).thenAnswer(call -> {
            LocalDate day = call.getArgument(1);
            return records.stream().filter(row -> row.observedSlot().toLocalDate().equals(day)).toList();
        });
        var reader = new StationInventoryReader(mock(JdbcTemplate.class), cache);

        assertThat(reader.read("ST-10", reference, true)).containsExactlyElementsOf(records.subList(1, 7));
        assertThat(reader.read("ST-10", reference)).containsExactlyElementsOf(records.subList(3, 7));
    }
}
