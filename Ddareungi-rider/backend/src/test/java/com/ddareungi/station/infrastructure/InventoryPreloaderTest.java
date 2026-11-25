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

    /** Late (backfilled) rows: the first refresh and then every 10 minutes re-copy the last 3 hours. */
    @Test void recentWindowIsRecopiedPeriodicallyForLateRows() {
        var jdbc = mock(JdbcTemplate.class);
        var store = mock(PreloadedInventoryStore.class);
        when(store.ready(any())).thenReturn(true);
        var checkpoint = LocalDateTime.now(PreloadedInventoryStore.KST).minusMinutes(1);
        when(store.checkpoint()).thenReturn(checkpoint);
        var preloader = new InventoryPreloader(jdbc, store);

        preloader.refresh();
        var firstFrom = firstQueryStart(jdbc);
        var expected = LocalDateTime.now(PreloadedInventoryStore.KST).minus(InventoryPreloader.LATE_ARRIVAL_WINDOW);
        assertThat(Duration.between(firstFrom, expected).abs()).isLessThan(Duration.ofMinutes(1));

        clearInvocations(jdbc);
        preloader.refresh(); // within 10 minutes: only the checkpoint overlap
        assertThat(firstQueryStart(jdbc)).isEqualTo(checkpoint.minus(InventoryPreloader.CHECKPOINT_OVERLAP));
    }

    @SuppressWarnings("unchecked")
    private static LocalDateTime firstQueryStart(JdbcTemplate jdbc) {
        var from = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jdbc, atLeastOnce()).query(anyString(), any(RowMapper.class), from.capture(), any());
        return (LocalDateTime) from.getAllValues().getFirst();
    }
}
