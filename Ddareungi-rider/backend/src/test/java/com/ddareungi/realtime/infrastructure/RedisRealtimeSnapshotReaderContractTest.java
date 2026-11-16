package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.application.RealtimeSnapshotReader;
import com.ddareungi.realtime.domain.PublicationGate;
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
import static org.mockito.Mockito.times;
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
        verify(redis, never()).hasKey(any());
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

    /**
     * Changed by review M1: this test used to assert that auto returns nothing when the pointer's hash expired.
     * A pointer left behind by a stopped collector or a rollback to the legacy writer would then hide all
     * realtime data, so auto now confirms the hash is missing (EXISTS) and reads the legacy layout instead.
     * Final behaviour is to be confirmed when the collector implements R2.
     */
    @Test
    void autoFallsBackToLegacyWhenPointerTargetsMissingVersionHash() {
        pointer("184211");
        hmget("station:realtime:v184211", List.of("ST-123", "ST-9"), Arrays.asList(null, null));
        when(redis.hasKey("station:realtime:v184211")).thenReturn(false);
        legacy(Arrays.asList(legacyFixture(), Map.of()));

        var auto = reader(RealtimeReadMode.AUTO);
        assertThat(auto.findAll(List.of("ST-123", "ST-9"))).containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
        // Repeated reads keep falling back (the warning is throttled, the fallback is not).
        assertThat(auto.findAll(List.of("ST-123", "ST-9"))).containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
        verify(redis, times(2)).hasKey("station:realtime:v184211");
    }

    @Test
    void versionedModeWithMissingVersionHashReturnsNothingAndDoesNotFallBack() {
        pointer("184211");
        hmget("station:realtime:v184211", List.of("ST-123", "ST-9"), Arrays.asList(null, null));
        when(redis.hasKey("station:realtime:v184211")).thenReturn(false);

        assertThat(reader(RealtimeReadMode.VERSIONED).findAll(List.of("ST-123", "ST-9"))).isEmpty();
        verify(redis, never()).executePipelined(any(RedisCallback.class));
        verify(redis, never()).hasKey(any());
    }

    @Test
    void autoWithExistingVersionHashLackingRequestedIdsLeavesThemUnknown() {
        pointer("184211");
        hmget("station:realtime:v184211", List.of("ST-123", "ST-9"), Arrays.asList(null, null));
        when(redis.hasKey("station:realtime:v184211")).thenReturn(true);

        assertThat(reader(RealtimeReadMode.AUTO).findAll(List.of("ST-123", "ST-9"))).isEmpty();
        verify(redis, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void autoPartialCoverageDoesNotFillMissingStationsFromLegacy() {
        pointer("184211");
        hmget("station:realtime:v184211", List.of("ST-123", "ST-9"), Arrays.asList(versionedValue(), null));

        assertThat(reader(RealtimeReadMode.AUTO).findAll(List.of("ST-123", "ST-9")))
                .containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
        verify(redis, never()).executePipelined(any(RedisCallback.class));
        // A returned value proves the hash exists, so no EXISTS round trip is made.
        verify(redis, never()).hasKey(any());
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

    /**
     * Changed by P2-1 (D3): this test used to also remove publicationEligible/operationStatus. A versioned value
     * without gate fields is now strictly excluded (see {@link #versionedValueWithoutGateFieldsIsExcluded}), so only
     * the internal snapshotVersion is removed here; the numeric-string parsing under test is unchanged.
     */
    @Test
    void acceptsSourceStyleNumericStrings() {
        var node = versionedNode("ST-123");
        node.put("rackTotCnt", "20");
        node.put("parkingBikeTotCnt", "7");
        node.put("shared", "35");
        node.put("stationLatitude", "37.4981");
        node.put("stationLongitude", "127.0282");
        node.remove("snapshotVersion");

        assertThat(readVersioned(node.toString())).isEqualTo(EXPECTED);
    }

    @Test
    void acceptsUtcTimestamp() {
        var node = versionedNode("ST-123");
        node.put("realtimeUpdatedAt", "2026-09-16T11:59:00Z");
        assertThat(readVersioned(node.toString())).isEqualTo(EXPECTED);
    }

    /**
     * Each mutation must make the station unknown (absent), never a zero count.
     * Changed by P2-1 (spec 11.0, A3): "remove:rackTotCnt" and "remove:shared" moved to
     * {@link #nullableRackAndSharedDoNotRejectTheEntry} because null alone no longer rejects an entry; present but
     * invalid values (negative, non-numeric) still do. The "too old" case moved from 331 s to 601 s because values
     * up to max-stale (600 s) are now served as STALE.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "remove:parkingBikeTotCnt", "null:parkingBikeTotCnt", "text::parkingBikeTotCnt", "text:unknown:parkingBikeTotCnt",
            "int:-1:parkingBikeTotCnt", "double:1.5:parkingBikeTotCnt", "bool:true:parkingBikeTotCnt",
            "int:-1:rackTotCnt", "double:1.5:rackTotCnt", "text:many:rackTotCnt", "bool:true:rackTotCnt",
            "double:-1:shared", "text:high:shared", "bool:true:shared",
            "remove:stationName", "text: :stationName", "int:1:stationName",
            "remove:stationLatitude", "double:999:stationLatitude", "double:-181:stationLongitude", "text:NaN:stationLatitude",
            "remove:realtimeUpdatedAt", "text:2026-09-16T20:59:00:realtimeUpdatedAt", "text:yesterday:realtimeUpdatedAt",
            "text:ST-999:stationId", "int:123:stationId",
            "bool:false:publicationEligible", "text:true:publicationEligible", "remove:publicationEligible",
            "remove:operationStatus", "text:INACTIVE:operationStatus", "text:UNKNOWN:operationStatus",
            "text:2026-09-16T11:49:29Z:realtimeUpdatedAt", "text:2026-09-16T12:00:01Z:realtimeUpdatedAt"})
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

    /** Changed by P2-1: the upper bound is now max-stale (600 s by default) instead of the old fixed 5 minutes. */
    @Test
    void acceptsBoundaryAges() {
        assertThat(reader(RealtimeReadMode.AUTO).maxAge()).isEqualTo(java.time.Duration.ofSeconds(600));
        var oldest = versionedNode("ST-123");
        oldest.put("realtimeUpdatedAt", CLOCK.instant().minus(reader(RealtimeReadMode.AUTO).maxAge()).toString());
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
        // Changed by P2-1: 11:50:00Z (570 s) is now STALE and served; 630 s is beyond max-stale (600 s).
        stale.put("updatedAt", "2026-09-16T11:49:00Z");
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
        // X3: legacy TTL 900 s outlives max-stale (600 s) so STALE values are still readable.
        verify(connection.keyCommands()).expire(any(), org.mockito.ArgumentMatchers.eq(900L));
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

    // ---- P2-1 (A3): nullable rack/shared, STALE window, publication gate ----

    @ParameterizedTest
    @ValueSource(strings = {"remove:rackTotCnt", "null:rackTotCnt", "blank:rackTotCnt",
            "remove:shared", "null:shared", "blank:shared"})
    void nullableRackAndSharedDoNotRejectTheEntry(String mutation) {
        var node = versionedNode("ST-123");
        String field = mutation.substring(mutation.indexOf(':') + 1);
        switch (mutation.substring(0, mutation.indexOf(':'))) {
            case "remove" -> node.remove(field);
            case "null" -> node.putNull(field);
            default -> node.put(field, " ");
        }
        var status = readVersioned(node.toString());
        assertThat(status).as(mutation).isNotNull();
        assertThat(status.availableBikeCount()).isEqualTo(7);
        if (field.equals("rackTotCnt")) assertThat(status.rackTotalCount()).isNull();
        else assertThat(status.sharedRate()).isNull();
    }

    @Test
    void sharedAboveOneHundredIsKeptAsIs() {
        var node = versionedNode("ST-123");
        node.put("shared", 135.5);
        assertThat(readVersioned(node.toString()).sharedRate()).isEqualTo(135.5);
    }

    @ParameterizedTest
    @ValueSource(longs = {121, 300, 599, 600})
    void staleValuesUpToMaxStaleAreServed(long ageSeconds) {
        var node = versionedNode("ST-123");
        node.put("realtimeUpdatedAt", CLOCK.instant().minusSeconds(ageSeconds).toString());
        assertThat(readVersioned(node.toString())).as("age %ds", ageSeconds).isNotNull();
        var tooOld = versionedNode("ST-123");
        tooOld.put("realtimeUpdatedAt", CLOCK.instant().minusSeconds(601).toString());
        assertThat(readVersioned(tooOld.toString())).isNull();
    }

    @Test
    void configuredMaxStaleBoundsTheReader() {
        var policy = new com.ddareungi.realtime.domain.RealtimeFreshnessPolicy(CLOCK, 60, 300);
        var node = versionedNode("ST-123");
        node.put("realtimeUpdatedAt", CLOCK.instant().minusSeconds(301).toString());
        pointer("184211");
        hmget("station:realtime:v184211", List.of("ST-123"), List.of(node.toString()));
        var reader = new RedisRealtimeSnapshotReader(redis, RealtimeReadMode.VERSIONED, policy);
        assertThat(reader.maxAge()).isEqualTo(java.time.Duration.ofSeconds(300));
        assertThat(reader.findAll(List.of("ST-123"))).isEmpty();
    }

    /** D3: versioned values are public only with publicationEligible=true and operationStatus=ACTIVE. */
    @ParameterizedTest
    @ValueSource(strings = {"remove:publicationEligible", "remove:operationStatus", "remove:both",
            "false:publicationEligible", "INACTIVE:operationStatus", "UNKNOWN:operationStatus", "null:operationStatus"})
    void versionedValueWithoutGateFieldsIsExcluded(String mutation) {
        var node = versionedNode("ST-123");
        switch (mutation) {
            case "remove:publicationEligible" -> node.remove("publicationEligible");
            case "remove:operationStatus" -> node.remove("operationStatus");
            case "remove:both" -> { node.remove("publicationEligible"); node.remove("operationStatus"); }
            case "false:publicationEligible" -> node.put("publicationEligible", false);
            case "null:operationStatus" -> node.putNull("operationStatus");
            default -> node.put("operationStatus", mutation.substring(0, mutation.indexOf(':')));
        }
        var snapshot = readVersionedSnapshot(node.toString());
        assertThat(snapshot.statuses()).as(mutation).isEmpty();
        assertThat(snapshot.excludedIds()).as(mutation).containsExactly("ST-123");
    }

    @Test
    void gateIsCheckedBeforePayloadAndIndependentlyOfAge() {
        // An ineligible value with broken counts and an expired time is still EXCLUDED, not merely unknown,
        // so an old or incomplete value can never resurrect a closed station.
        var node = versionedNode("ST-123");
        node.put("operationStatus", "INACTIVE");
        node.remove("parkingBikeTotCnt");
        node.put("realtimeUpdatedAt", "2026-09-16T09:00:00Z");
        assertThat(readVersionedSnapshot(node.toString()).excludedIds()).containsExactly("ST-123");
    }

    @Test
    void eligibleFixtureIsPublicAndNotExcluded() {
        var snapshot = readVersionedSnapshot(versionedValue());
        assertThat(snapshot.statuses()).containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
        assertThat(snapshot.excludedIds()).isEmpty();
    }

    @Test
    void corruptValuesAreUnknownNotExcluded() {
        var otherStation = versionedNode("ST-999");
        assertThat(readVersionedSnapshot("not json").excludedIds()).isEmpty();
        assertThat(readVersionedSnapshot(otherStation.toString()).excludedIds()).isEmpty();
    }

    @Test
    void gateCombinationRules() {
        assertThat(PublicationGate.of(PublicationGate.ELIGIBLE, PublicationGate.ELIGIBLE)).isEqualTo(PublicationGate.ELIGIBLE);
        assertThat(PublicationGate.of(PublicationGate.MISSING, PublicationGate.INELIGIBLE)).isEqualTo(PublicationGate.INELIGIBLE);
        assertThat(PublicationGate.of(PublicationGate.ELIGIBLE, PublicationGate.MISSING)).isEqualTo(PublicationGate.MISSING);
        assertThat(PublicationGate.of(PublicationGate.INELIGIBLE, PublicationGate.ELIGIBLE)).isEqualTo(PublicationGate.INELIGIBLE);
    }

    /** Legacy hashes carry no gate (D3 limitation): valid id/name/coordinates are enough. */
    @Test
    void legacyValuesHaveNoGateAndAreNeverExcluded() {
        legacy(List.of(legacyFixture()));
        var snapshot = reader(RealtimeReadMode.LEGACY).read(List.of("ST-123"));
        assertThat(snapshot.statuses()).containsExactlyEntriesOf(Map.of("ST-123", EXPECTED));
        assertThat(snapshot.excludedIds()).isEmpty();
    }

    @Test
    void legacyNullableFieldsAndBlankNames() {
        var noRack = new HashMap<>(legacyFixture());
        noRack.remove("rackTotalCount");
        noRack.remove("sharedRate");
        var blankName = new HashMap<>(legacyFixture());
        blankName.put("stationName", " ");
        legacy(List.of(noRack, blankName));

        var result = reader(RealtimeReadMode.LEGACY).findAll(List.of("A", "B"));

        assertThat(result).containsOnlyKeys("A");
        assertThat(result.get("A").rackTotalCount()).isNull();
        assertThat(result.get("A").sharedRate()).isNull();
        assertThat(result.get("A").availableBikeCount()).isEqualTo(7);
    }

    @Test
    void legacyWriterOmitsNullRackAndShared() {
        var connection = mock(RedisConnection.class);
        var hashCommands = mock(RedisHashCommands.class);
        when(connection.hashCommands()).thenReturn(hashCommands);
        when(connection.keyCommands()).thenReturn(mock(RedisKeyCommands.class));
        doAnswer(invocation -> {
            invocation.<RedisCallback<?>>getArgument(0).doInRedis(connection);
            return List.of();
        }).when(redis).executePipelined(any(RedisCallback.class));
        var written = new LinkedHashMap<String, String>();
        doAnswer(invocation -> {
            invocation.<Map<byte[], byte[]>>getArgument(1).forEach((field, value) ->
                    written.put(new String(field, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8)));
            return null;
        }).when(hashCommands).hMSet(any(), any());

        new RedisRealtimeStationStore(redis, mock(RealtimeSnapshotReader.class)).saveAll(List.of(
                new RealtimeStationStatus("ST-123", "강남역 2번 출구", null, 7, null, 37.4981, 127.0282, UPDATED)));

        assertThat(written).doesNotContainKeys("rackTotalCount", "sharedRate").containsEntry("availableBikeCount", "7");
        assertThat(RealtimeSnapshotValues.fromLegacyHash("ST-123", written)).get()
                .extracting(RealtimeStationStatus::rackTotalCount, RealtimeStationStatus::sharedRate)
                .containsExactly(null, null);
    }

    @Test
    void storeReadDelegatesToReader() {
        var reader = mock(RealtimeSnapshotReader.class);
        var snapshot = new com.ddareungi.realtime.application.RealtimeSnapshot(Map.of("ST-123", EXPECTED), java.util.Set.of("ST-9"));
        when(reader.read(List.of("ST-123", "ST-9"))).thenReturn(snapshot);
        assertThat(new RedisRealtimeStationStore(redis, reader).read(List.of("ST-123", "ST-9"))).isEqualTo(snapshot);
    }

    private com.ddareungi.realtime.application.RealtimeSnapshot readVersionedSnapshot(String raw) {
        pointer("184211");
        hmget("station:realtime:v184211", List.of("ST-123"), Arrays.asList(raw));
        return reader(RealtimeReadMode.VERSIONED).read(List.of("ST-123"));
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
