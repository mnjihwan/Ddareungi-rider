package com.ddareungi.station.infrastructure;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InventoryDayCacheTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final LocalDate today = LocalDate.now(ZoneOffset.ofHours(9));
    final InventoryDayCache cache = new InventoryDayCache(redis, mock(PreloadedInventoryStore.class));
    InventoryDayCacheTest() { when(redis.opsForValue()).thenReturn(values); }

    @Test void cachedDayAvoidsDatabaseAndPreservesZero() {
        when(values.get(anyString())).thenReturn("v1;0:0;1435:8");
        Supplier<List<StationInventoryReader.Observation>> loader = mock(Supplier.class);
        var rows = cache.get("ST-1", today.minusDays(1), loader);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().availableBikes()).isZero();
        assertThat(rows.getLast().observedSlot().toLocalTime()).isEqualTo(LocalTime.of(23,55));
        verifyNoInteractions(loader);
    }

    @Test void todayAndMissingHistoryExpireQuicklyButHistoricalValuesLastLonger() {
        var row = new StationInventoryReader.Observation("ST-1", today.atStartOfDay().atOffset(ZoneOffset.ofHours(9)), 2);
        cache.get("ST-1", today, () -> List.of(row));
        verify(values).set(endsWith(today.toString()), eq("v1;0:2"), eq(Duration.ofSeconds(30)));
        cache.get("ST-1", today.minusDays(1), List::of);
        verify(values).set(endsWith(today.minusDays(1).toString()), eq("v1"), eq(Duration.ofSeconds(30)));
        cache.get("ST-1", today.minusDays(7), () -> List.of(row));
        verify(values).set(endsWith(today.minusDays(7).toString()), eq("v1;0:2"), eq(Duration.ofHours(6)));
    }

    @Test void corruptEntryAndRedisOutageFallBackToSource() {
        when(values.get(anyString())).thenReturn("v1;0:-1");
        var source = List.of(new StationInventoryReader.Observation("ST-1", today.atStartOfDay().atOffset(ZoneOffset.ofHours(9)), 4));
        assertThat(cache.get("ST-1", today, () -> source)).isEqualTo(source);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        doThrow(new RedisConnectionFailureException("offline")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertThat(cache.get("ST-1", today, () -> source)).isEqualTo(source);
    }

    @Test void futureDatesAreNotLoadedAndOldDatesAreNotCached() {
        Supplier<List<StationInventoryReader.Observation>> loader = mock(Supplier.class);
        assertThat(cache.get("ST-1", today.plusDays(1), loader)).isEmpty();
        verifyNoInteractions(loader, values);
        when(loader.get()).thenReturn(List.of());
        cache.get("ST-1", today.minusDays(9), loader);
        verify(loader).get();
        verifyNoInteractions(values);
    }
}
