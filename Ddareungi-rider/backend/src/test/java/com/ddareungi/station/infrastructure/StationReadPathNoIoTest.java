package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.infrastructure.RealtimeReadMode;
import com.ddareungi.realtime.infrastructure.RealtimeSnapshotCache;
import com.ddareungi.realtime.infrastructure.RealtimeSnapshotCacheProperties;
import com.ddareungi.station.application.StationMapProperties;
import com.ddareungi.station.application.StationMapService;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R3 completion criterion: once the catalog is loaded and the snapshot polled, request-path port calls perform zero
 * JdbcTemplate and zero Redis interactions; Redis outages keep the last snapshot, which ages STALE → UNAVAILABLE.
 */
class StationReadPathNoIoTest {
    private static final Instant T0 = Instant.parse("2026-11-16T03:00:00Z");
    private static final Location CENTER = new Location(37.4979, 127.0276);

    private final Clock[] clock = {Clock.fixed(T0, ZoneOffset.UTC)};
    private final Clock delegating = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return clock[0].instant(); }
    };
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private HashOperations<String, Object, Object> hashes;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn((List) List.of(
                new StationCatalog.Row("ST-1", "강남역 2번 출구", "서울 강남구 강남대로", 37.4979, 127.0276, 20),
                new StationCatalog.Row("ST-2", "역삼역 4번 출구", "서울 강남구 테헤란로", 37.5006, 127.0364, 15),
                new StationCatalog.Row("ST-3", "폐쇄 대여소", "서울", 37.4985, 127.0280, 10),
                new StationCatalog.Row("ST-4", null, "이름 없음", 37.4985, 127.0280, 10)));
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("5", T0.toString()));
        when(hashes.entries("station:realtime:v5")).thenReturn(Map.of(
                "ST-1", versioned("ST-1", true), "ST-2", versioned("ST-2", true), "ST-3", versioned("ST-3", false)));
        Map<String, String> legacy = new HashMap<>();
        legacy.put("stationName", "legacy");
        legacy.put("availableBikeCount", "2");
        legacy.put("latitude", "37.4979");
        legacy.put("longitude", "127.0276");
        legacy.put("updatedAt", T0.toString());
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(new ArrayList<Object>(List.of(legacy, legacy)));
    }

    private static String versioned(String id, boolean eligible) {
        return "{\"stationName\":\"" + id + " live\",\"parkingBikeTotCnt\":\"3\",\"stationLatitude\":\"37.4979\","
                + "\"stationLongitude\":\"127.0276\",\"realtimeUpdatedAt\":\"" + T0 + "\",\"publicationEligible\":" + eligible
                + ",\"operationStatus\":\"ACTIVE\"}";
    }

    private RealtimeFreshnessPolicy policy() { return RealtimeFreshnessPolicy.defaults(delegating); }

    @ParameterizedTest
    @EnumSource(RealtimeReadMode.class)
    void requestPathPerformsNoJdbcOrRedisInteractions(RealtimeReadMode mode) {
        if (mode == RealtimeReadMode.LEGACY) when(values.multiGet(anyList())).thenReturn(Arrays.asList(null, T0.toString()));
        var cache = new RealtimeSnapshotCache(redis, mode, policy(), RealtimeSnapshotCacheProperties.defaults());
        var adapter = new JdbcStationSearchAdapter(jdbc, cache, policy());
        assertThat(adapter.reloadCatalog()).isTrue();
        assertThat(cache.poll(adapter.catalogStationIds())).isTrue();
        clearInvocations(jdbc, redis, values, hashes);
        // R4: the map service (viewport cap + clusters) reads the same in-memory port.
        var map = new StationMapService(adapter, new StationViewMapper(policy()), StationMapProperties.defaults());
        var bounds = new StationBounds(37.49, 37.51, 127.02, 127.04);

        for (int i = 0; i < 3; i++) {
            assertThat(adapter.findCandidatesInBounds(bounds)).extracting(StationCandidate::status)
                    .contains(RealtimeFreshness.FRESH);
            assertThat(map.viewport(bounds).items()).isNotEmpty();
            for (int level = 5; level <= 8; level++) assertThat(map.clusters(bounds, level).items()).isNotEmpty();
            assertThat(adapter.findNearby(CENTER, 1000)).isNotEmpty();
            assertThat(adapter.findWithin(CENTER, 1000)).extracting(StationCandidate::status).contains(RealtimeFreshness.FRESH);
            assertThat(adapter.findInBounds(new StationBounds(37.49, 37.51, 127.02, 127.04))).isNotEmpty();
            assertThat(adapter.findByIds(List.of("ST-1", "ST-2"), CENTER)).isNotEmpty();
            assertThat(adapter.searchByName("강남역", CENTER)).isNotEmpty();
            assertThat(adapter.lookup(List.of("ST-1", "ST-3", "ST-4", "nope"))).extracting(StationLookup::outcome)
                    .contains(StationLookup.Outcome.FOUND, StationLookup.Outcome.EXCLUDED, StationLookup.Outcome.NOT_FOUND);
            assertThat(adapter.realtimeSummary().lastSuccessAt()).isEqualTo(T0);
        }

        verifyNoInteractions(jdbc, redis, values, hashes);
    }

    @Test
    void redisOutageKeepsTheLastSnapshotWhichAgesFromStaleToUnavailable() {
        var cache = new RealtimeSnapshotCache(redis, RealtimeReadMode.AUTO, policy(), RealtimeSnapshotCacheProperties.defaults());
        var adapter = new JdbcStationSearchAdapter(jdbc, cache, policy());
        adapter.reloadCatalog();
        cache.poll(adapter.catalogStationIds());
        when(values.multiGet(anyList())).thenThrow(new QueryTimeoutException("redis down"));

        assertThat(statusAfter(adapter, cache, 120)).isEqualTo(RealtimeFreshness.FRESH);
        assertThat(statusAfter(adapter, cache, 121)).isEqualTo(RealtimeFreshness.STALE);
        assertThat(statusAfter(adapter, cache, 600)).isEqualTo(RealtimeFreshness.STALE);
        assertThat(statusAfter(adapter, cache, 601)).isEqualTo(RealtimeFreshness.UNAVAILABLE);
        var summary = adapter.realtimeSummary();
        assertThat(summary.unavailable()).isEqualTo(2);
        assertThat(summary.lastSuccessAt()).as("cached last-success-at survives the outage").isEqualTo(T0);
    }

    private RealtimeFreshness statusAfter(JdbcStationSearchAdapter adapter, RealtimeSnapshotCache cache, long seconds) {
        clock[0] = Clock.fixed(T0.plus(Duration.ofSeconds(seconds)), ZoneOffset.UTC);
        assertThat(cache.poll(adapter.catalogStationIds())).isFalse();
        return adapter.lookup(List.of("ST-1")).getFirst().candidate().status();
    }
}
