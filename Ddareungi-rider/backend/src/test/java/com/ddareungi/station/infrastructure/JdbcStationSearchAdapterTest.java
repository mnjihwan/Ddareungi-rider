package com.ddareungi.station.infrastructure;

import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import com.ddareungi.realtime.infrastructure.RedisRealtimeStationStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcStationSearchAdapterTest {
    @Test void searchesCachedCatalogButRefreshesLiveCountsForEverySearch() {
        var jdbc = mock(JdbcTemplate.class);
        var realtime = mock(RedisRealtimeStationStore.class);
        var center = new Location(37.4979, 127.0276);
        var candidates = List.of(new Station("ST-1", "강남역 2번 출구", "서울", center, 20, null, 0, null));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Station>>any())).thenReturn(candidates);
        when(realtime.findAll(List.of("ST-1"))).thenReturn(Map.of());
        var adapter = new JdbcStationSearchAdapter(jdbc, realtime);
        for (String query : List.of("rkdska역", "ㄱㄴㅇ", "강남얙"))
            assertThat(adapter.searchByName(query, center)).as(query).extracting(Station::stationId).containsExactly("ST-1");
        verify(jdbc, times(1)).query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Station>>any());
        verify(realtime, times(3)).findAll(List.of("ST-1"));
    }
    @Test
    void joinsRealtimeCountsFiltersRadiusAndKeepsUnknownCountsNull() {
        var jdbc = mock(JdbcTemplate.class);
        var realtime = mock(RedisRealtimeStationStore.class);
        var center = new Location(37.4979, 127.0276);
        var candidates = List.of(
                new Station("near", "near", "address", center, 20, null, 0, null),
                new Station("unknown", "unknown", "address", new Location(37.5, 127.0276), 20, null, 0, null),
                new Station("far", "far", "address", new Location(37.6, 127.0276), 20, null, 0, null));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Station>>any(), any(Object[].class))).thenReturn(candidates);
        var now = Instant.now();
        when(realtime.findAll(List.of("near", "unknown", "far"))).thenReturn(Map.of(
                "near", new RealtimeStationStatus("near", "actual name", 20, 0, 0, center.latitude(), center.longitude(), now)));

        var result = new JdbcStationSearchAdapter(jdbc, realtime).findNearby(center, 1000);

        assertThat(result).extracting(Station::stationId).containsExactly("near", "unknown");
        assertThat(result.getFirst().availableBikeCount()).isZero();
        assertThat(result.getFirst().updatedAt()).isEqualTo(now);
        assertThat(result.get(1).availableBikeCount()).isNull();
        assertThat(result.get(1).updatedAt()).isNull();
    }
}
