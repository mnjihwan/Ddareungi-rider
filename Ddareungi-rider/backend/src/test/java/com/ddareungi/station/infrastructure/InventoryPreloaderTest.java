package com.ddareungi.station.infrastructure;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InventoryPreloaderTest {
    @Test void boundedCopyDoesNotAdvanceCheckpointOnWriteFailure() {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(PreloadedInventoryStore.class);
        when(store.ready(any())).thenReturn(true);
        when(store.checkpoint()).thenReturn(LocalDateTime.now(PreloadedInventoryStore.KST).minusMinutes(1));
        doThrow(new IllegalStateException("unavailable")).when(store).save(anyList());
        new InventoryPreloader(jdbc, store).refresh();
        verify(store, never()).completed(any());
    }
    @Test void coldStartPublishesEightDaysOnlyAfterEachDayIsLoaded() {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(PreloadedInventoryStore.class);
        new InventoryPreloader(jdbc, store).refresh();
        verify(store, times(8)).markReady(any());
        var calls = new java.util.ArrayList<>(mockingDetails(store).getInvocations());
        for (int index = 0; index < calls.size(); index++) {
            if (calls.get(index).getMethod().getName().equals("markReady"))
                assertThat(calls.get(index - 1).getMethod().getName()).isEqualTo("save");
        }
        verify(store).completed(any());
    }
    @Test void failedWarmupNeverPublishesPartialDay() {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(PreloadedInventoryStore.class);
        doThrow(new IllegalStateException("offline")).when(store).save(anyList());
        new InventoryPreloader(jdbc, store).refresh();
        verify(store, never()).markReady(any());
        verify(store, never()).completed(any());
    }
    @Test void copyBoundsQueriesAtOneHourAcrossMidnight() {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(PreloadedInventoryStore.class);
        var from = LocalDateTime.parse("2026-10-28T23:30:00");
        new InventoryPreloader(jdbc, store).copy(from, from.plusMinutes(90));
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StationInventoryReader.Observation>>any(), eq(from), eq(from.plusHours(1)));
        verify(jdbc).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<StationInventoryReader.Observation>>any(), eq(from.plusHours(1)), eq(from.plusMinutes(90)));
    }
}
