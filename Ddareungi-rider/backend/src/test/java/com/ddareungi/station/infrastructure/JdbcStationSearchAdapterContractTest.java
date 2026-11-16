package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.application.RealtimeSnapshot;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import com.ddareungi.realtime.infrastructure.RedisRealtimeStationStore;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** P2-1 port contract (findWithin / lookup / realtimeSummary, gate exclusion, nullable columns) for the JDBC adapter. */
class JdbcStationSearchAdapterContractTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final Location CENTER = new Location(37.4979, 127.0276);

    private JdbcTemplate jdbc;
    private RedisRealtimeStationStore realtime;
    private JdbcStationSearchAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        realtime = mock(RedisRealtimeStationStore.class);
        adapter = new JdbcStationSearchAdapter(jdbc, realtime, RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static Station catalog(String id, double latitude) {
        return new Station(id, id + " name", "서울", new Location(latitude, 127.0276), 20, null, 0, null);
    }

    private static RealtimeStationStatus status(String id, double latitude, long ageSeconds) {
        return new RealtimeStationStatus(id, id + " live", null, 4, 135.0, latitude, 127.0276, NOW.minusSeconds(ageSeconds));
    }

    private void nearbyRows(List<Station> rows) {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Station>>any(), any(Object[].class))).thenReturn(rows);
    }

    @Test
    void findWithinClassifiesEveryCandidateAndOmitsExcludedStations() {
        nearbyRows(List.of(catalog("fresh", 37.4980), catalog("stale", 37.4985), catalog("none", 37.4990),
                catalog("closed", 37.4981), catalog("b-tie", 37.4995), catalog("a-tie", 37.4995)));
        when(realtime.read(anyList())).thenReturn(new RealtimeSnapshot(Map.of(
                "fresh", status("fresh", 37.4980, 120),
                "stale", status("stale", 37.4985, 121),
                "a-tie", status("a-tie", 37.4995, 0),
                "b-tie", status("b-tie", 37.4995, 0)), Set.of("closed")));

        List<StationCandidate> result = adapter.findWithin(CENTER, 1000);

        assertThat(result).extracting(StationCandidate::stationId).containsExactly("fresh", "stale", "none", "a-tie", "b-tie");
        assertThat(result).extracting(StationCandidate::status).containsExactly(RealtimeFreshness.FRESH,
                RealtimeFreshness.STALE, RealtimeFreshness.UNAVAILABLE, RealtimeFreshness.FRESH, RealtimeFreshness.FRESH);
        assertThat(result).extracting(StationCandidate::ageSeconds).containsExactly(120L, 121L, null, 0L, 0L);
        var fresh = result.getFirst().station();
        assertThat(fresh.stationName()).isEqualTo("fresh live");
        assertThat(fresh.rackTotalCount()).isNull();       // realtime value had no rack count
        assertThat(fresh.shared()).isEqualTo(135.0);       // > 100 passed through
        var none = result.get(2).station();
        assertThat(none.availableBikeCount()).isNull();
        assertThat(none.updatedAt()).isNull();
        assertThat(none.shared()).isNull();
    }

    @Test
    void legacyMethodsDropGateExcludedStationsButKeepUnknownOnes() {
        nearbyRows(List.of(catalog("open", 37.4980), catalog("closed", 37.4981), catalog("unknown", 37.4982)));
        when(realtime.read(anyList())).thenReturn(new RealtimeSnapshot(
                Map.of("open", status("open", 37.4980, 10)), Set.of("closed")));

        var result = adapter.findNearby(CENTER, 1000);

        assertThat(result).extracting(Station::stationId).containsExactly("open", "unknown");
        assertThat(result.get(1).availableBikeCount()).isNull();
        assertThat(result.getFirst().shared()).isEqualTo(135.0);
    }

    @Test
    void realtimeStoreFailureMakesCandidatesUnavailableInsteadOfFailing() {
        nearbyRows(List.of(catalog("a", 37.4980)));
        when(realtime.read(anyList())).thenThrow(new QueryTimeoutException("down"));

        assertThat(adapter.findWithin(CENTER, 1000)).extracting(StationCandidate::status)
                .containsExactly(RealtimeFreshness.UNAVAILABLE);
    }

    @Test
    void lookupReportsFoundNotFoundExcludedAndUnavailableInFirstSeenOrder() throws Exception {
        List<Object[]> rows = List.of(
                row("fresh", "강남", 37.5, 127.0, 20), row("stale", "역삼", 37.5, 127.0, null),
                row("old", "선릉", 37.5, 127.0, 10), row("gate", "폐쇄", 37.5, 127.0, 10),
                row("nocoord", "좌표없음", null, null, 10), row("blank", " ", 37.5, 127.0, 10));
        stubCatalogRows(rows);
        when(realtime.read(anyList())).thenReturn(new RealtimeSnapshot(Map.of(
                "fresh", status("fresh", 37.5, 30), "stale", status("stale", 37.5, 400)), Set.of("gate")));

        var result = adapter.lookup(List.of("stale", "missing", "fresh", "stale", "gate", "old", "nocoord", "blank"));

        assertThat(result).extracting(StationLookup::stationId)
                .containsExactly("stale", "missing", "fresh", "gate", "old", "nocoord", "blank");
        assertThat(result).extracting(StationLookup::outcome).containsExactly(
                StationLookup.Outcome.FOUND, StationLookup.Outcome.NOT_FOUND, StationLookup.Outcome.FOUND,
                StationLookup.Outcome.EXCLUDED, StationLookup.Outcome.UNAVAILABLE, StationLookup.Outcome.EXCLUDED,
                StationLookup.Outcome.EXCLUDED);
        assertThat(result.getFirst().candidate().status()).isEqualTo(RealtimeFreshness.STALE);
        assertThat(result.getFirst().candidate().station().distanceMeters()).isZero();
        assertThat(result.get(2).candidate().status()).isEqualTo(RealtimeFreshness.FRESH);
        assertThat(result.get(4).candidate().station().stationName()).isEqualTo("선릉");
        assertThat(result.get(4).candidate().station().availableBikeCount()).isNull();
        // Only catalog-valid ids are sent to the realtime store.
        verify(realtime).read(List.of("stale", "fresh", "gate", "old")); // catalog-valid ids only, request order
    }

    @Test
    void lookupOfNothingTouchesNoStorage() {
        assertThat(adapter.lookup(List.of())).isEmpty();
        verifyNoInteractions(jdbc, realtime);
    }

    @Test
    void realtimeSummaryCountsPublicStationsOnly() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Station>>any())).thenReturn(List.of(
                catalog("f", 37.5), catalog("s", 37.5), catalog("u", 37.5), catalog("x", 37.5)));
        when(realtime.read(List.of("f", "s", "u", "x"))).thenReturn(new RealtimeSnapshot(Map.of(
                "f", status("f", 37.5, 0), "s", status("s", 37.5, 600)), Set.of("x")));
        Instant lastSuccess = NOW.minusSeconds(42);
        when(realtime.lastSuccessAt()).thenReturn(Optional.of(lastSuccess));

        var summary = adapter.realtimeSummary();

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.fresh()).isEqualTo(1);
        assertThat(summary.stale()).isEqualTo(1);
        assertThat(summary.unavailable()).isEqualTo(1);
        assertThat(summary.lastSuccessAt()).isEqualTo(lastSuccess);
        assertThat(summary.evaluatedAt()).isEqualTo(NOW);
    }

    @Test
    void realtimeSummaryWithRedisDownIsAllUnavailable() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Station>>any())).thenReturn(List.of(catalog("a", 37.5)));
        when(realtime.read(anyList())).thenThrow(new QueryTimeoutException("down"));
        when(realtime.lastSuccessAt()).thenThrow(new QueryTimeoutException("down"));

        var summary = adapter.realtimeSummary();

        assertThat(summary.unavailable()).isEqualTo(1);
        assertThat(summary.lastSuccessAt()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowMapperKeepsNullRackCountNull() throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), (RowMapper<Station>) captor.capture(), any(Object[].class))).thenReturn(List.of());
        when(realtime.read(anyList())).thenReturn(RealtimeSnapshot.empty());
        adapter.findNearby(CENTER, 1000);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("station_id")).thenReturn("ST-1");
        when(rs.getString("station_name")).thenReturn("이름");
        when(rs.getDouble("latitude")).thenReturn(37.5);
        when(rs.getDouble("longitude")).thenReturn(127.0);
        when(rs.getObject("rack_total_count", Integer.class)).thenReturn(null);
        Station station = (Station) captor.getValue().mapRow(rs, 0);
        assertThat(station.rackTotalCount()).isNull();
        verify(rs, never()).getInt(anyString());
    }

    private static Object[] row(String id, String name, Double latitude, Double longitude, Integer rack) {
        return new Object[]{id, name, latitude, longitude, rack};
    }

    /** Feeds lookup's catalog query through its real RowMapper with mocked ResultSets. */
    @SuppressWarnings("unchecked")
    private void stubCatalogRows(List<Object[]> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            List<Object> mapped = new ArrayList<>();
            for (Object[] values : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("station_id")).thenReturn((String) values[0]);
                when(rs.getString("station_name")).thenReturn((String) values[1]);
                when(rs.getObject("latitude", Double.class)).thenReturn((Double) values[2]);
                when(rs.getObject("longitude", Double.class)).thenReturn((Double) values[3]);
                when(rs.getObject("rack_total_count", Integer.class)).thenReturn((Integer) values[4]);
                mapped.add(mapper.mapRow(rs, mapped.size()));
            }
            return mapped;
        });
    }
}
