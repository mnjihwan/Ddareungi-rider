package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.application.CachedRealtimeSnapshot;
import com.ddareungi.realtime.application.RealtimeSnapshot;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changed by P2-1: the adapter read {@code RedisRealtimeStationStore.read} instead of {@code findAll}.
 * Changed by R3 (structure only): the catalog is one full query at reload time and realtime values come from the
 * in-memory snapshot source, so "refreshes live counts for every search" is now asserted by changing the snapshot
 * between searches (the poller's job) instead of counting per-search Redis reads; the catalog is still queried once.
 */
class JdbcStationSearchAdapterTest {
    @Test
    @SuppressWarnings("unchecked")
    void searchesCachedCatalogButRefreshesLiveCountsForEverySearch() {
        var jdbc = mock(JdbcTemplate.class);
        var center = new Location(37.4979, 127.0276);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn((List) List.of(
                new StationCatalog.Row("ST-1", "강남역 2번 출구", "서울", center.latitude(), center.longitude(), 20)));
        var live = new AtomicReference<>(CachedRealtimeSnapshot.empty());
        var adapter = new JdbcStationSearchAdapter(jdbc, now -> live.get(), RealtimeFreshnessPolicy.defaults());
        adapter.reloadCatalog();
        int bikes = 0;
        for (String query : List.of("rkdska역", "ㄱㄴㅇ", "강남얙")) {
            bikes++;
            live.set(new CachedRealtimeSnapshot(new RealtimeSnapshot(Map.of("ST-1", new RealtimeStationStatus("ST-1",
                    "강남역 2번 출구", 20, bikes, 5, center.latitude(), center.longitude(), Instant.now())), Set.of()), null, null));
            assertThat(adapter.searchByName(query, center)).as(query).extracting(Station::stationId, Station::availableBikeCount)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("ST-1", bikes));
        }
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void joinsRealtimeCountsFiltersRadiusAndKeepsUnknownCountsNull() {
        var jdbc = mock(JdbcTemplate.class);
        var center = new Location(37.4979, 127.0276);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn((List) List.of(
                new StationCatalog.Row("near", "near", "address", center.latitude(), center.longitude(), 20),
                new StationCatalog.Row("unknown", "unknown", "address", 37.5, 127.0276, 20),
                new StationCatalog.Row("far", "far", "address", 37.6, 127.0276, 20)));
        var now = Instant.now();
        var snapshot = new CachedRealtimeSnapshot(new RealtimeSnapshot(Map.of(
                "near", new RealtimeStationStatus("near", "actual name", 20, 0, 0, center.latitude(), center.longitude(), now)), Set.of()), null, now);
        var adapter = new JdbcStationSearchAdapter(jdbc, ignored -> snapshot, RealtimeFreshnessPolicy.defaults());
        adapter.reloadCatalog();

        var result = adapter.findNearby(center, 1000);

        assertThat(result).extracting(Station::stationId).containsExactly("near", "unknown");
        assertThat(result.getFirst().availableBikeCount()).isZero();
        assertThat(result.getFirst().updatedAt()).isEqualTo(now);
        assertThat(result.get(1).availableBikeCount()).isNull();
        assertThat(result.get(1).updatedAt()).isNull();
    }
}
