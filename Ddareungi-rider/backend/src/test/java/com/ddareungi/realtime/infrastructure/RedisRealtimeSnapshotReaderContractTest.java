package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.application.RealtimeSnapshotReader;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the realtime Redis read side against the fixture
 * {@code realtime/redis-realtime-contract.json} (API spec v5 21.2 example; design proposal).
 */
class RedisRealtimeSnapshotReaderContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Fixture realtimeUpdatedAt is 2026-09-16T20:59:00+09:00; read 30 seconds later. */
    private static final Instant UPDATED = Instant.parse("2026-09-16T11:59:00Z");
    private static final Clock CLOCK = Clock.fixed(UPDATED.plusSeconds(30), ZoneOffset.UTC);
    private static final RealtimeStationStatus EXPECTED =
            new RealtimeStationStatus("ST-123", "강남역 2번 출구", 20, 7, 35.0, 37.4981, 127.0282, UPDATED);

    private JsonNode fixture;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private HashOperations<String, Object, Object> hashes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/realtime/redis-realtime-contract.json")) {
            fixture = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        hashes = mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);
    }

    @Test
    void fixtureUsesSourceFieldNamesFromSpec() {
        var names = new TreeSet<String>();
        fixture.path("versioned").path("fields").path("ST-123").propertyNames().forEach(names::add);
        assertThat(names).containsExactlyInAnyOrder("rackTotCnt", "stationName", "parkingBikeTotCnt", "shared",
                "stationLatitude", "stationLongitude", "stationId", "realtimeUpdatedAt", "snapshotVersion",
                "operationStatus", "publicationEligible");
        assertThat(fixture.path("versioned").path("hashKey").asString())
                .isEqualTo(RedisRealtimeSnapshotReader.VERSION_KEY_PREFIX + fixture.path("versioned").path("pointerValue").asString());
        assertThat(fixture.path("versioned").path("pointerKey").asString()).isEqualTo(RedisRealtimeSnapshotReader.CURRENT_KEY);
    }

    @Test
    void autoReadsVersionedHashThroughPointerWithGetThenHmget() {
        pointer(fixture.path("versioned").path("pointerValue").asString());
        hmget(fixture.path("versioned").path("hashKey").asString(), List.of("ST-123"), List.of(versionedValue()));

        var result = reader(RealtimeReadMode.AUTO).findAll(List.of("ST-123"));

        assertThat(result).containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
        verify(values).get("station:realtime:current");
        verify(hashes).multiGet("station:realtime:v184211", List.<Object>of("ST-123"));
        verify(redis, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void autoFallsBackToLegacyHashesWhenPointerIsAbsent() {
        pointer(null);
        legacy(List.of(legacyFixture()));

        var result = reader(RealtimeReadMode.AUTO).findAll(List.of("ST-123"));

        assertThat(result).containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
        verify(hashes, never()).multiGet(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "abc", "-1", "1.5", "v184211"})
    void malformedPointerIsTreatedAsAbsent(String pointer) {
        pointer(pointer);
        legacy(List.of(legacyFixture()));

        assertThat(reader(RealtimeReadMode.AUTO).findAll(List.of("ST-123"))).containsKey("ST-123");
        assertThat(reader(RealtimeReadMode.VERSIONED).findAll(List.of("ST-123"))).isEmpty();
        verify(hashes, never()).multiGet(any(), any());
    }

    @Test
    void expiredVersionHashMeansUnknownAndDoesNotFallBackToLegacy() {
        pointer("184211");
        hmget("station:realtime:v184211", List.of("ST-123", "ST-9"), Arrays.asList(null, null));

        assertThat(reader(RealtimeReadMode.AUTO).findAll(List.of("ST-123", "ST-9"))).isEmpty();
        verify(redis, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void legacyModeNeverReadsPointer() {
        legacy(List.of(legacyFixture()));

        assertThat(reader(RealtimeReadMode.LEGACY).findAll(List.of("ST-123"))).containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
        verify(values, never()).get(any());
    }

    @Test
    void versionedModeWithoutPointerReturnsNothingInsteadOfLegacy() {
        pointer(null);

        assertThat(reader(RealtimeReadMode.VERSIONED).findAll(List.of("ST-123"))).isEmpty();
        verify(redis, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void emptyRequestDoesNotTouchRedis() {
        assertThat(reader(RealtimeReadMode.AUTO).findAll(List.of())).isEmpty();
        verify(redis, never()).opsForValue();
    }

    @Test
    void mixedVersionKeepsValidEntriesAndDropsMissingOrCorruptOnes() {
        pointer("184211");
        var zero = versionedNode("ST-0");
        zero.put("parkingBikeTotCnt", 0);
        var corrupt = versionedNode("ST-BAD");
        corrupt.put("parkingBikeTotCnt", "unknown");
        hmget("station:realtime:v184211", List.of("ST-123", "ST-0", "ST-MISSING", "ST-BAD"),
                Arrays.asList(versionedValue(), zero.toString(), null, corrupt.toString()));

        var result = reader(RealtimeReadMode.AUTO).findAll(List.of("ST-123", "ST-0", "ST-MISSING", "ST-BAD"));

        assertThat(result).containsOnlyKeys("ST-123", "ST-0");
        assertThat(result.get("ST-0").availableBikeCount()).isZero();
    }

    @Test
    void acceptsSourceStyleNumericStrings() {
        var node = versionedNode("ST-123");
        node.put("rackTotCnt", "20");
        node.put("parkingBikeTotCnt", "7");
        node.put("shared", "35");
        node.put("stationLatitude", "37.4981");
        node.put("stationLongitude", "127.0282");
        node.remove("publicationEligible");
        node.remove("snapshotVersion");
        node.remove("operationStatus");

        assertThat(readVersioned(node.toString())).isEqualTo(EXPECTED);
    }

    @Test
    void acceptsUtcTimestamp() {
        var node = versionedNode("ST-123");
        node.put("realtimeUpdatedAt", "2026-09-16T11:59:00Z");
        assertThat(readVersioned(node.toString())).isEqualTo(EXPECTED);
    }

    /** Each mutation must make the station unknown (absent), never a zero count. */
    @ParameterizedTest
    @ValueSource(strings = {
            "remove:parkingBikeTotCnt", "null:parkingBikeTotCnt", "text::parkingBikeTotCnt", "text:unknown:parkingBikeTotCnt",
            "int:-1:parkingBikeTotCnt", "double:1.5:parkingBikeTotCnt", "bool:true:parkingBikeTotCnt",
            "remove:rackTotCnt", "int:-1:rackTotCnt", "remove:shared", "double:-1:shared",
            "remove:stationName", "text: :stationName", "int:1:stationName",
            "remove:stationLatitude", "double:999:stationLatitude", "double:-181:stationLongitude", "text:NaN:stationLatitude",
            "remove:realtimeUpdatedAt", "text:2026-09-16T20:59:00:realtimeUpdatedAt", "text:yesterday:realtimeUpdatedAt",
            "text:ST-999:stationId", "int:123:stationId",
            "bool:false:publicationEligible", "text:true:publicationEligible",
            "text:2026-09-16T11:53:59Z:realtimeUpdatedAt", "text:2026-09-16T12:00:01Z:realtimeUpdatedAt"})
    void rejectsCorruptMissingHiddenOrStaleValues(String mutation) {
        var node = versionedNode("ST-123");
        String[] parts = mutation.split(":", -1);
        String field = parts[parts.length - 1];
        String value = parts.length > 2 ? mutation.substring(parts[0].length() + 1, mutation.length() - field.length() - 1) : null;
        switch (parts[0]) {
            case "remove" -> node.remove(field);
            case "null" -> node.putNull(field);
            case "text" -> node.put(field, value);
            case "int" -> node.put(field, Integer.parseInt(value));
            case "double" -> node.put(field, Double.parseDouble(value));
            case "bool" -> node.put(field, Boolean.parseBoolean(value));
            default -> throw new IllegalArgumentException(mutation);
        }
        assertThat(readVersioned(node.toString())).as(mutation).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "\"text\"", "{", "null"})
    void rejectsNonObjectValues(String raw) {
        assertThat(readVersioned(raw)).isNull();
    }

    @Test
    void acceptsBoundaryAges() {
        var oldest = versionedNode("ST-123");
        oldest.put("realtimeUpdatedAt", CLOCK.instant().minus(RedisRealtimeSnapshotReader.MAX_AGE).toString());
        assertThat(readVersioned(oldest.toString())).isNotNull();
        var newest = versionedNode("ST-123");
        newest.put("realtimeUpdatedAt", CLOCK.instant().plus(RedisRealtimeSnapshotReader.MAX_CLOCK_SKEW).toString());
        assertThat(readVersioned(newest.toString())).isNotNull();
    }

    @Test
    void legacyCorruptEntriesAreUnknownNotZero() {
        var missingCount = new HashMap<>(legacyFixture());
        missingCount.remove("availableBikeCount");
        var negative = new HashMap<>(legacyFixture());
        negative.put("availableBikeCount", "-1");
        var badLatitude = new HashMap<>(legacyFixture());
        badLatitude.put("latitude", "999");
        var stale = new HashMap<>(legacyFixture());
        stale.put("updatedAt", "2026-09-16T11:50:00Z");
        legacy(List.of(missingCount, negative, badLatitude, stale, Map.of()));

        assertThat(reader(RealtimeReadMode.LEGACY).findAll(List.of("A", "B", "C", "D", "E"))).isEmpty();
    }

    @Test
    void redisFailurePropagatesAsDataAccessException() {
        when(values.get(any())).thenThrow(new QueryTimeoutException("down"));
        assertThatThrownBy(() -> reader(RealtimeReadMode.AUTO).findAll(List.of("ST-123")))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void legacyWriterAndReaderShareTheFixtureContract() {
        var connection = mock(RedisConnection.class);
        var hashCommands = mock(RedisHashCommands.class);
        when(connection.hashCommands()).thenReturn(hashCommands);
        when(connection.keyCommands()).thenReturn(mock(RedisKeyCommands.class));
        doAnswer(invocation -> {
            invocation.<RedisCallback<?>>getArgument(0).doInRedis(connection);
            return List.of();
        }).when(redis).executePipelined(any(RedisCallback.class));
        var written = new LinkedHashMap<String, String>();
        var key = new ArrayList<String>();
        doAnswer(invocation -> {
            key.add(new String(invocation.<byte[]>getArgument(0), StandardCharsets.UTF_8));
            invocation.<Map<byte[], byte[]>>getArgument(1).forEach((field, value) ->
                    written.put(new String(field, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8)));
            return null;
        }).when(hashCommands).hMSet(any(), any());

        new RedisRealtimeStationStore(redis, mock(RealtimeSnapshotReader.class)).saveAll(List.of(EXPECTED));

        assertThat(key).containsExactly(fixture.path("legacy").path("hashKey").asString());
        assertThat(written).isEqualTo(legacyFixture());
        verify(connection.keyCommands()).expire(any(), anyLong());
    }

    @Test
    void storeFindAllDelegatesToReader() {
        var reader = mock(RealtimeSnapshotReader.class);
        when(reader.findAll(List.of("ST-123"))).thenReturn(Map.of("ST-123", EXPECTED));
        assertThat(new RedisRealtimeStationStore(redis, reader).findAll(List.of("ST-123")))
                .containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
    }

    @Test
    void readModeParsing() {
        assertThat(RealtimeReadMode.parse(null)).isEqualTo(RealtimeReadMode.AUTO);
        assertThat(RealtimeReadMode.parse("")).isEqualTo(RealtimeReadMode.AUTO);
        assertThat(RealtimeReadMode.parse("auto")).isEqualTo(RealtimeReadMode.AUTO);
        assertThat(RealtimeReadMode.parse("LEGACY")).isEqualTo(RealtimeReadMode.LEGACY);
        assertThat(RealtimeReadMode.parse(" versioned ")).isEqualTo(RealtimeReadMode.VERSIONED);
        assertThatThrownBy(() -> RealtimeReadMode.parse("redis")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RedisRealtimeSnapshotReader(redis, "legacy").mode()).isEqualTo(RealtimeReadMode.LEGACY);
    }

    private RealtimeStationStatus readVersioned(String raw) {
        pointer("184211");
        hmget("station:realtime:v184211", List.of("ST-123"), Arrays.asList(raw));
        return reader(RealtimeReadMode.VERSIONED).findAll(List.of("ST-123")).get("ST-123");
    }

    private RedisRealtimeSnapshotReader reader(RealtimeReadMode mode) {
        return new RedisRealtimeSnapshotReader(redis, mode, CLOCK);
    }

    private void pointer(String value) {
        when(values.get("station:realtime:current")).thenReturn(value);
    }

    private void hmget(String key, List<String> ids, List<?> result) {
        when(hashes.multiGet(key, List.<Object>copyOf(ids))).thenReturn(new ArrayList<Object>(result));
    }

    private void legacy(List<?> result) {
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(new ArrayList<Object>(result));
    }

    private String versionedValue() {
        return fixture.path("versioned").path("fields").path("ST-123").toString();
    }

    private ObjectNode versionedNode(String stationId) {
        var node = (ObjectNode) fixture.path("versioned").path("fields").path("ST-123").deepCopy();
        node.put("stationId", stationId);
        return node;
    }

    private Map<String, String> legacyFixture() {
        var map = new LinkedHashMap<String, String>();
        fixture.path("legacy").path("fields").properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asString()));
        return map;
    }
}
