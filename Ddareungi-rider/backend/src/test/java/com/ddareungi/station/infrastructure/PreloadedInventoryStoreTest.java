package com.ddareungi.station.infrastructure;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PreloadedInventoryStoreTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final PreloadedInventoryStore store = new PreloadedInventoryStore(redis);
    final LocalDate today = LocalDate.now(PreloadedInventoryStore.KST);

    @Test void loadedDayPreservesZeroAndSortsTimes() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of("hit", "1435", "4", "0", "0"));
        var rows = store.read("ST-1", today).orElseThrow();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().availableBikes()).isZero();
        assertThat(rows.getLast().observedSlot().toLocalTime()).isEqualTo(LocalTime.of(23,55));
    }
    @Test void confirmedEmptyDayDiffersFromMissingOrCorruptCache() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of("hit"), List.of("miss"), List.of("hit", "0", "-1"));
        assertThat(store.read("ST-1", today)).hasValue(List.of());
        assertThat(store.read("ST-1", today)).isEmpty();
        assertThat(store.read("ST-1", today)).isEmpty();
    }
    @Test void retentionUsesKoreaCalendarDaysIncludingYearBoundary() {
        assertThat(PreloadedInventoryStore.expires(LocalDate.of(2026,12,25)))
            .isEqualTo(Instant.parse("2027-01-01T15:00:00Z"));
        assertThat(store.read("ST-1", today.minusDays(8))).isEmpty();
        assertThat(store.read("ST-1", today.plusDays(1))).isEmpty();
        verifyNoInteractions(redis);
    }
    @Test void preloadedDataBypassesBothMysqlAndRequestCache() {
        var preload = mock(PreloadedInventoryStore.class);
        var rows = List.of(new StationInventoryReader.Observation("ST-1", today.atStartOfDay().atOffset(PreloadedInventoryStore.KST), 3));
        when(preload.read("ST-1", today)).thenReturn(Optional.of(rows));
        var result = new InventoryDayCache(redis, preload).get("ST-1", today, () -> { throw new AssertionError("DB must not be queried"); });
        assertThat(result).isEqualTo(rows);
        verifyNoInteractions(redis);
    }
}
