package com.ddareungi.station.application;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.ddareungi.realtime.domain.RealtimeFreshness.FRESH;
import static com.ddareungi.realtime.domain.RealtimeFreshness.STALE;
import static com.ddareungi.realtime.domain.RealtimeFreshness.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A5: nearby filter → sort → limit and the spec 11.0 decision table, against a mocked findWithin. */
class StationQueryServiceNearbyTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final Location CENTER = new Location(37.5, 127.0);
    private final StationSearchPort port = mock(StationSearchPort.class);
    private final StationQueryService service = new StationQueryService(port);

    private static StationCandidate c(String id, String name, Integer bikes, double distance, RealtimeFreshness status) {
        boolean servable = status.servable();
        long age = status == FRESH ? 10 : 300;
        var station = new Station(id, name, "서울", new Location(37.5, 127.0), 10, servable ? bikes : null, distance,
                servable ? NOW.minusSeconds(age) : null, null);
        return new StationCandidate(station, status, servable ? age : null);
    }

    private NearbyStationsView run(List<StationCandidate> candidates, int limit, NearbySort sort, boolean onlyAvailable) {
        when(port.findWithin(CENTER, 1000)).thenReturn(candidates);
        return service.nearby(new NearbyQuery(CENTER, 1000, limit, sort, onlyAvailable));
    }

    private static List<String> ids(NearbyStationsView view) {
        return view.items().stream().map(item -> item.station().stationId()).toList();
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.REALTIME_DATA_UNAVAILABLE));
    }

    @Test
    void distanceSortBreaksTiesByStationId() {
        var view = run(List.of(c("B", "b", 1, 50, FRESH), c("A", "a", 1, 50, FRESH), c("C", "c", 1, 10, FRESH)),
                30, NearbySort.DISTANCE, false);
        assertThat(ids(view)).containsExactly("C", "A", "B");
        assertThat(view.partial()).isFalse();
        assertThat(view.center()).isEqualTo(CENTER);
        assertThat(view.radiusMeters()).isEqualTo(1000);
        verify(port).findWithin(CENTER, 1000);
    }

    @Test
    void bikeCountSortIsDescendingThenDistanceThenIdAndRanksTheWholeRadiusBeforeLimit() {
        // The best station is the farthest: a nearest-first cut before sorting would miss it.
        var view = run(List.of(
                c("near", "n", 1, 10, FRESH),
                c("tieB", "t", 5, 300, FRESH),
                c("tieA", "t", 5, 300, FRESH),
                c("mid", "m", 5, 200, FRESH),
                c("far", "f", 20, 990, FRESH)), 3, NearbySort.PARKING_BIKE_TOT_CNT, false);
        assertThat(ids(view)).containsExactly("far", "mid", "tieA");
    }

    @Test
    void nameSortUsesNfcAndCodePointOrder() {
        String decomposed = "가"; // NFD of 가 (U+AC00)
        String supplementary = new String(Character.toChars(0x1F6B2)); // bicycle emoji, above U+FFFF
        var view = run(List.of(
                c("emoji", supplementary, 1, 1, FRESH),
                c("private", "", 1, 2, FRESH),
                c("nfd", decomposed, 1, 3, FRESH),
                c("hangul2", "나", 1, 4, FRESH),
                c("dupB", "같음", 1, 5, FRESH),
                c("dupA", "같음", 1, 6, FRESH)), 30, NearbySort.NAME, false);
        // 가(AC00, after NFC) < 같(AC19) < 나(B098) < U+E000 < U+1F6B2 (UTF-16 order would put the emoji before U+E000).
        assertThat(ids(view)).containsExactly("nfd", "dupA", "dupB", "hangul2", "private", "emoji");
    }

    @Test
    void codePointComparisonHandlesPrefixes() {
        assertThat(StationQueryService.compareCodePoints("ab", "abc")).isNegative();
        assertThat(StationQueryService.compareCodePoints("abc", "ab")).isPositive();
        assertThat(StationQueryService.compareCodePoints("ab", "ab")).isZero();
    }

    @Test
    void limitTruncationAloneIsNotPartial() {
        var view = run(List.of(c("A", "a", 1, 1, FRESH), c("B", "b", 1, 2, FRESH), c("C", "c", 1, 3, STALE)),
                2, NearbySort.DISTANCE, false);
        assertThat(ids(view)).containsExactly("A", "B");
        assertThat(view.partial()).isFalse();
    }

    // ---- decision table (spec 11.0) ----

    @Test
    void noCandidatesIsEmptyAndNotPartial() {
        assertThat(run(List.of(), 30, NearbySort.DISTANCE, false).items()).isEmpty();
        var onlyAvailable = run(List.of(), 30, NearbySort.DISTANCE, true);
        assertThat(onlyAvailable.items()).isEmpty();
        assertThat(onlyAvailable.partial()).isFalse();
    }

    @Test
    void allFreshZeroReturnsZeroItemsButOnlyAvailableIsEmpty() {
        var general = run(List.of(c("A", "a", 0, 1, FRESH)), 30, NearbySort.DISTANCE, false);
        assertThat(ids(general)).containsExactly("A");
        assertThat(general.items().getFirst().station().availableBikeCount()).isZero();
        var only = run(List.of(c("A", "a", 0, 1, FRESH)), 30, NearbySort.DISTANCE, true);
        assertThat(only.items()).isEmpty();
        assertThat(only.partial()).isFalse();
    }

    @Test
    void freshPositiveWithOthersOnlyAvailableReturnsFreshPositiveAndIsPartial() {
        var candidates = List.of(c("pos", "p", 3, 1, FRESH), c("zero", "z", 0, 2, FRESH),
                c("stale", "s", 9, 3, STALE), c("down", "d", null, 4, UNAVAILABLE));
        var general = run(candidates, 30, NearbySort.DISTANCE, false);
        assertThat(ids(general)).containsExactly("pos", "zero", "stale");
        assertThat(general.warnings()).containsExactly(WarningCode.STALE_DATA, WarningCode.REALTIME_DATA_UNAVAILABLE);
        var only = run(candidates, 30, NearbySort.DISTANCE, true);
        assertThat(ids(only)).containsExactly("pos");
        assertThat(only.partial()).isTrue();
        assertThat(only.warnings()).containsExactly(WarningCode.STALE_DATA, WarningCode.REALTIME_DATA_UNAVAILABLE);
    }

    @Test
    void freshZeroMixedWithUnconfirmedIsPartialAndOnlyAvailableEmptyPartial() {
        var candidates = List.of(c("zero", "z", 0, 1, FRESH), c("down", "d", null, 2, UNAVAILABLE));
        var general = run(candidates, 30, NearbySort.DISTANCE, false);
        assertThat(ids(general)).containsExactly("zero");
        assertThat(general.partial()).isTrue();
        assertThat(general.warnings()).containsExactly(WarningCode.REALTIME_DATA_UNAVAILABLE);
        var only = run(candidates, 30, NearbySort.DISTANCE, true);
        assertThat(only.items()).isEmpty();
        assertThat(only.partial()).isTrue();
    }

    @Test
    void staleOnlyIsPartialListButOnlyAvailableIs503() {
        var candidates = List.of(c("s1", "a", 2, 1, STALE), c("down", "d", null, 2, UNAVAILABLE));
        var general = run(candidates, 30, NearbySort.DISTANCE, false);
        assertThat(ids(general)).containsExactly("s1");
        assertThat(general.items().getFirst().realtimeStatus()).isEqualTo(STALE);
        assertThat(general.partial()).isTrue();
        assertThat(general.warnings()).containsExactly(WarningCode.STALE_DATA, WarningCode.REALTIME_DATA_UNAVAILABLE);
        assertUnavailable(() -> run(candidates, 30, NearbySort.DISTANCE, true));
    }

    @Test
    void allUnavailableIs503ForBothModes() {
        var candidates = List.of(c("d1", "a", null, 1, UNAVAILABLE), c("d2", "b", null, 2, UNAVAILABLE));
        assertUnavailable(() -> run(candidates, 30, NearbySort.DISTANCE, false));
        assertUnavailable(() -> run(candidates, 30, NearbySort.DISTANCE, true));
    }

    @Test
    void sortParameterValues() {
        assertThat(NearbySort.fromParameter("distance")).isEqualTo(NearbySort.DISTANCE);
        assertThat(NearbySort.fromParameter("parkingBikeTotCnt")).isEqualTo(NearbySort.PARKING_BIKE_TOT_CNT);
        assertThat(NearbySort.fromParameter("name")).isEqualTo(NearbySort.NAME);
        assertThatThrownBy(() -> NearbySort.fromParameter("Name")).isInstanceOf(ApiException.class);
    }
}
