package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** R3 background poller: reload only on pointer / last-success-at change, keep the previous snapshot on failures. */
class RealtimeSnapshotCacheTest {
    private static final Instant T0 = Instant.parse("2026-11-16T03:00:00Z");
    private static final List<String> IDS = List.of("ST-1", "ST-2");

    private final MutableClock clock = new MutableClock(T0);
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private HashOperations<String, Object, Object> hashes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);
    }

    private RealtimeSnapshotCache cache(RealtimeReadMode mode) {
        return new RealtimeSnapshotCache(redis, mode, RealtimeFreshnessPolicy.defaults(clock),
                RealtimeSnapshotCacheProperties.defaults());
    }

    private void pointers(String current, String lastSuccess) {
        when(values.multiGet(anyList())).thenReturn(Arrays.asList(current, lastSuccess));
    }

    static String versionedJson(String id, int bikes, Instant updatedAt, boolean eligible) {
        return "{\"stationId\":\"" + id + "\",\"stationName\":\"" + id + " live\",\"rackTotCnt\":\"20\",\"parkingBikeTotCnt\":\""
                + bikes + "\",\"shared\":\"35\",\"stationLatitude\":\"37.5\",\"stationLongitude\":\"127.0\",\"realtimeUpdatedAt\":\""
                + updatedAt.atOffset(ZoneOffset.ofHours(9)) + "\",\"publicationEligible\":" + eligible + ",\"operationStatus\":\"ACTIVE\"}";
    }

    static Map<String, String> legacyHash(String id, int bikes, Instant updatedAt) {
        Map<String, String> hash = new HashMap<>();
        hash.put("stationName", id + " legacy");
        hash.put("availableBikeCount", Integer.toString(bikes));
        hash.put("latitude", "37.5");
        hash.put("longitude", "127.0");
        hash.put("updatedAt", updatedAt.toString());
        return hash;
    }

    @SuppressWarnings("unchecked")
    private void legacy(List<Map<String, String>> perId) {
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(new ArrayList<Object>(perId));
    }

    @Test
    void versionedReloadsOnlyWhenThePointerChanges() {
        var cache = cache(RealtimeReadMode.AUTO);
        pointers("7", T0.toString());
        when(hashes.entries("station:realtime:v7")).thenReturn(Map.of(
                "ST-1", versionedJson("ST-1", 3, T0, true), "ST-2", versionedJson("ST-2", 0, T0, false)));

        assertThat(cache.poll(IDS)).isTrue();
        var first = cache.current(T0);
        assertThat(first.snapshot().statuses()).containsOnlyKeys("ST-1");
        assertThat(first.snapshot().statuses().get("ST-1").availableBikeCount()).isEqualTo(3);
        assertThat(first.snapshot().excludedIds()).containsExactly("ST-2");
        assertThat(first.lastSuccessAt()).isEqualTo(T0);

        // Pointer unchanged: one MGET per poll, no HGETALL, same snapshot instance.
        clock.advance(Duration.ofSeconds(5));
        assertThat(cache.poll(IDS)).isFalse();
        assertThat(cache.poll(IDS)).isFalse();
        verify(hashes, times(1)).entries(anyString());
        assertThat(cache.current(T0)).isSameAs(first);
        verify(redis, never()).executePipelined(any(RedisCallback.class));

        // Pointer moved: exactly one more HGETALL of the new version.
        pointers("8", T0.plusSeconds(60).toString());
        when(hashes.entries("station:realtime:v8")).thenReturn(Map.of("ST-1", versionedJson("ST-1", 9, T0.plusSeconds(60), true)));
        assertThat(cache.poll(IDS)).isTrue();
        verify(hashes).entries("station:realtime:v8");
        assertThat(cache.current(T0).snapshot().statuses().get("ST-1").availableBikeCount()).isEqualTo(9);
        assertThat(cache.current(T0).snapshot().excludedIds()).isEmpty();
        assertThat(cache.current(T0).lastSuccessAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void legacyReloadsOnLastSuccessChangeAndEverySixtySecondsOtherwise() {
        var cache = cache(RealtimeReadMode.LEGACY);
        pointers(null, T0.toString());
        legacy(List.of(legacyHash("ST-1", 4, T0), Map.of()));

        assertThat(cache.poll(IDS)).isTrue();
        assertThat(cache.current(T0).snapshot().statuses()).containsOnlyKeys("ST-1");

        clock.advance(Duration.ofSeconds(30));
        assertThat(cache.poll(IDS)).as("unchanged last-success-at within 60 s").isFalse();
        verify(redis, times(1)).executePipelined(any(RedisCallback.class));

        pointers(null, T0.plusSeconds(30).toString());
        assertThat(cache.poll(IDS)).as("last-success-at changed").isTrue();
        verify(redis, times(2)).executePipelined(any(RedisCallback.class));

        clock.advance(Duration.ofSeconds(59));
        assertThat(cache.poll(IDS)).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.poll(IDS)).as("60 s safety reload").isTrue();
        verify(redis, times(3)).executePipelined(any(RedisCallback.class));

        // A new catalog id list (another instance) forces a reload as well.
        assertThat(cache.poll(List.of("ST-1", "ST-2", "ST-3"))).isTrue();
        verify(hashes, never()).entries(anyString());
    }

    @Test
    void autoUsesLegacyWhenThePointerNamesAMissingHash() {
        var cache = cache(RealtimeReadMode.AUTO);
        pointers("9", T0.toString());
        when(hashes.entries("station:realtime:v9")).thenReturn(Map.of());
        legacy(List.of(legacyHash("ST-1", 2, T0), legacyHash("ST-2", 1, T0)));

        assertThat(cache.poll(IDS)).isTrue();

        assertThat(cache.current(T0).snapshot().statuses()).containsOnlyKeys("ST-1", "ST-2");
        assertThat(cache.current(T0).snapshot().statuses().get("ST-1").stationName()).isEqualTo("ST-1 legacy");
    }

    @Test
    void versionedModeNeverFallsBackToLegacy() {
        var cache = cache(RealtimeReadMode.VERSIONED);
        pointers(null, T0.toString());

        cache.poll(IDS);

        assertThat(cache.current(T0).snapshot().statuses()).isEmpty();
        verify(redis, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void redisFailureKeepsThePreviousSnapshotAndLastSuccess() {
        var cache = cache(RealtimeReadMode.AUTO);
        pointers("7", T0.toString());
        when(hashes.entries("station:realtime:v7")).thenReturn(Map.of("ST-1", versionedJson("ST-1", 3, T0, true)));
        cache.poll(IDS);
        var before = cache.current(T0);

        when(values.multiGet(anyList())).thenThrow(new QueryTimeoutException("down"));
        clock.advance(Duration.ofSeconds(700));
        assertThat(cache.poll(IDS)).isFalse();

        assertThat(cache.current(clock.instant())).isSameAs(before);
        assertThat(before.lastSuccessAt()).isEqualTo(T0);
        assertThat(before.snapshot().statuses()).containsKey("ST-1"); // aging into UNAVAILABLE is the reader's job
    }

    @Test
    void currentNeverTouchesRedis() {
        var cache = cache(RealtimeReadMode.AUTO);
        pointers("7", T0.toString());
        when(hashes.entries("station:realtime:v7")).thenReturn(Map.of("ST-1", versionedJson("ST-1", 3, T0, true)));
        cache.poll(IDS);
        clearInvocations(redis, values, hashes);

        for (int i = 0; i < 10; i++) cache.current(T0);

        verifyNoInteractions(redis, values, hashes);
    }

    /** Settable clock for poll timing. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }
}
