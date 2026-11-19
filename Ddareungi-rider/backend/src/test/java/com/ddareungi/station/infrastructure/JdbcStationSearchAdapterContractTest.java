package com.ddareungi.station.infrastructure;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.realtime.application.CachedRealtimeSnapshot;
import com.ddareungi.realtime.application.RealtimeSnapshot;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import com.ddareungi.realtime.infrastructure.RealtimeReadMode;
import com.ddareungi.realtime.infrastructure.RealtimeSnapshotCache;
import com.ddareungi.realtime.infrastructure.RealtimeSnapshotCacheProperties;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2-1 port contract (findWithin / lookup / realtimeSummary, gate exclusion, nullable columns) for the JDBC adapter.
 *
 * <p>Changed by R3 (structure only, same expectations): the adapter no longer queries MySQL/Redis per call. Catalog
 * rows are now stubbed on the single full catalog query ({@link JdbcStationSearchAdapter#CATALOG_SQL}, loaded by
 * {@code reloadCatalog()}), and realtime values come from an in-memory snapshot source instead of
 * {@code RedisRealtimeStationStore.read}. The former "only catalog-valid ids are sent to the realtime store"
 * verification became "no storage is touched on the request path" (see {@link StationReadPathNoIoTest}). The Redis
 * failure cases use the real {@link RealtimeSnapshotCache} with a failing Redis.
 */
class JdbcStationSearchAdapterContractTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final Location CENTER = new Location(37.4979, 127.0276);
    private static final RealtimeFreshnessPolicy POLICY = RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC));

    private JdbcTemplate jdbc;
    private final AtomicReference<CachedRealtimeSnapshot> realtime = new AtomicReference<>(CachedRealtimeSnapshot.empty());
    private JdbcStationSearchAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        adapter = new JdbcStationSearchAdapter(jdbc, now -> realtime.get(), POLICY);
    }

    private static StationCatalog.Row catalog(String id, double latitude) {
        return new StationCatalog.Row(id, id + " name", "서울", latitude, 127.0276, 20);
    }

    private static RealtimeStationStatus status(String id, double latitude, long ageSeconds) {
        return new RealtimeStationStatus(id, id + " live", null, 4, 135.0, latitude, 127.0276, NOW.minusSeconds(ageSeconds));
    }

    @SuppressWarnings("unchecked")
    private void catalogRows(List<StationCatalog.Row> rows) {
        when(jdbc.query(eq(JdbcStationSearchAdapter.CATALOG_SQL), any(RowMapper.class))).thenReturn((List) rows);
        assertThat(adapter.reloadCatalog()).isTrue();
    }

    private void snapshot(Map<String, RealtimeStationStatus> statuses, Set<String> excluded) {
        realtime.set(new CachedRealtimeSnapshot(new RealtimeSnapshot(statuses, excluded), null, NOW));
    }

    @Test
    void findWithinClassifiesEveryCandidateAndOmitsExcludedStations() {
        catalogRows(List.of(catalog("fresh", 37.4980), catalog("stale", 37.4985), catalog("none", 37.4990),
                catalog("closed", 37.4981), catalog("b-tie", 37.4995), catalog("a-tie", 37.4995)));
        snapshot(Map.of(
                "fresh", status("fresh", 37.4980, 120),
                "stale", status("stale", 37.4985, 121),
                "a-tie", status("a-tie", 37.4995, 0),
                "b-tie", status("b-tie", 37.4995, 0)), Set.of("closed"));

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
        catalogRows(List.of(catalog("open", 37.4980), catalog("closed", 37.4981), catalog("unknown", 37.4982)));
        snapshot(Map.of("open", status("open", 37.4980, 10)), Set.of("closed"));

        var result = adapter.findNearby(CENTER, 1000);

        assertThat(result).extracting(Station::stationId).containsExactly("open", "unknown");
        assertThat(result.get(1).availableBikeCount()).isNull();
        assertThat(result.getFirst().shared()).isEqualTo(135.0);
        assertThat(adapter.findInBounds(new StationBounds(37.49, 37.51, 127.02, 127.03)))
                .extracting(Station::stationId).containsExactlyInAnyOrder("open", "unknown");
        assertThat(adapter.findByIds(List.of("closed", "open", "missing"), CENTER)).extracting(Station::stationId)
                .containsExactly("open");
    }

    @Test
    void valuesOlderThanMaxStaleAreNotJoined() {
        catalogRows(List.of(catalog("old", 37.4980)));
        snapshot(Map.of("old", status("old", 37.4980, 601)), Set.of());

        var station = adapter.findNearby(CENTER, 1000).getFirst();

        assertThat(station.availableBikeCount()).isNull();
        assertThat(station.stationName()).isEqualTo("old name");
        assertThat(adapter.findWithin(CENTER, 1000)).extracting(StationCandidate::status).containsExactly(RealtimeFreshness.UNAVAILABLE);
    }

    @Test
    void realtimeStoreFailureMakesCandidatesUnavailableInsteadOfFailing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new QueryTimeoutException("down"));
        var cache = new RealtimeSnapshotCache(redis, RealtimeReadMode.AUTO, POLICY, RealtimeSnapshotCacheProperties.defaults());
        adapter = new JdbcStationSearchAdapter(jdbc, cache, POLICY);
        catalogRows(List.of(catalog("a", 37.4980)));
        assertThat(cache.poll(adapter.catalogStationIds())).isFalse();

        assertThat(adapter.findWithin(CENTER, 1000)).extracting(StationCandidate::status)
                .containsExactly(RealtimeFreshness.UNAVAILABLE);
    }

    @Test
    void lookupReportsFoundNotFoundExcludedAndUnavailableInFirstSeenOrder() {
        stubCatalogResultSets(List.of(
                row("fresh", "강남", 37.5, 127.0, 20), row("stale", "역삼", 37.5, 127.0, null),
                row("old", "선릉", 37.5, 127.0, 10), row("gate", "폐쇄", 37.5, 127.0, 10),
                row("nocoord", "좌표없음", null, null, 10), row("blank", " ", 37.5, 127.0, 10)));
        assertThat(adapter.reloadCatalog()).isTrue();
        snapshot(Map.of("fresh", status("fresh", 37.5, 30), "stale", status("stale", 37.5, 400)), Set.of("gate"));

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
    }

    @Test
    void lookupOfNothingTouchesNoStorage() {
        assertThat(adapter.lookup(List.of())).isEmpty(); // even before any catalog is loaded
        verifyNoInteractions(jdbc);
    }

    @Test
    void realtimeSummaryCountsPublicStationsOnly() {
        catalogRows(List.of(catalog("f", 37.5), catalog("s", 37.5), catalog("u", 37.5), catalog("x", 37.5)));
        Instant lastSuccess = NOW.minusSeconds(42);
        realtime.set(new CachedRealtimeSnapshot(new RealtimeSnapshot(Map.of(
                "f", status("f", 37.5, 0), "s", status("s", 37.5, 600)), Set.of("x")), lastSuccess, NOW));

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
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new QueryTimeoutException("down"));
        var cache = new RealtimeSnapshotCache(redis, RealtimeReadMode.AUTO, POLICY, RealtimeSnapshotCacheProperties.defaults());
        adapter = new JdbcStationSearchAdapter(jdbc, cache, POLICY);
        catalogRows(List.of(catalog("a", 37.5)));
        cache.poll(adapter.catalogStationIds());

        var summary = adapter.realtimeSummary();

        assertThat(summary.unavailable()).isEqualTo(1);
        assertThat(summary.lastSuccessAt()).isNull();
    }

    @Test
    void rowMapperKeepsNullRackCountNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("station_id")).thenReturn("ST-1");
        when(rs.getString("station_name")).thenReturn("이름");
        when(rs.getObject("latitude", Double.class)).thenReturn(37.5);
        when(rs.getObject("longitude", Double.class)).thenReturn(127.0);
        when(rs.getObject("rack_total_count", Integer.class)).thenReturn(null);

        StationCatalog.Row row = JdbcStationSearchAdapter.ROW.mapRow(rs, 0);

        assertThat(row.rackTotalCount()).isNull();
        verify(rs, never()).getInt(anyString());
    }

    @Test
    void everyPortMethodAnswers503UntilTheCatalogHasLoaded() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenThrow(new QueryTimeoutException("mysql down"));
        assertThat(adapter.reloadCatalog()).isFalse();

        List<Consumer<JdbcStationSearchAdapter>> calls = List.of(
                a -> a.findNearby(CENTER, 1000), a -> a.findWithin(CENTER, 1000), a -> a.findByIds(List.of("x"), CENTER),
                a -> a.searchByName("강남역", CENTER), a -> a.findInBounds(new StationBounds(37.49, 37.51, 127.02, 127.03)),
                a -> a.lookup(List.of("x")), JdbcStationSearchAdapter::realtimeSummary);
        for (var call : calls) {
            assertThatThrownBy(() -> call.accept(adapter)).isInstanceOfSatisfying(ApiException.class,
                    exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }
        assertThat(adapter.catalogLoaded()).isFalse();
        assertThat(adapter.catalogStationIds()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedReloadKeepsThePreviousCatalog() {
        catalogRows(List.of(catalog("kept", 37.4980)));
        when(jdbc.query(anyString(), any(RowMapper.class))).thenThrow(new QueryTimeoutException("mysql down"));

        assertThat(adapter.reloadCatalog()).isFalse();

        assertThat(adapter.findNearby(CENTER, 1000)).extracting(Station::stationId).containsExactly("kept");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn((List) List.of(catalog("next", 37.4980)));
        assertThat(adapter.reloadCatalog()).isTrue();
        assertThat(adapter.findNearby(CENTER, 1000)).extracting(Station::stationId).containsExactly("next");
    }

    private static Object[] row(String id, String name, Double latitude, Double longitude, Integer rack) {
        return new Object[]{id, name, latitude, longitude, rack};
    }

    /** Feeds the catalog query through its real RowMapper with mocked ResultSets. */
    @SuppressWarnings("unchecked")
    private void stubCatalogResultSets(List<Object[]> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
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
