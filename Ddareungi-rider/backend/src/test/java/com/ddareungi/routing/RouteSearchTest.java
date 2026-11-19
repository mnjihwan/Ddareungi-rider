package com.ddareungi.routing;

import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A6 planner rules with stubbed matrices: options, ranking and tie-breaks, partial failures, availability, recheck. */
class RouteSearchTest {
    static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    final StationSearchPort stations = mock(StationSearchPort.class);
    final WalkingRouteProvider walking = mock(WalkingRouteProvider.class);
    final BicycleRouteProvider bicycle = mock(BicycleRouteProvider.class);
    final Location origin = new Location(37.50, 127.02), destination = new Location(37.52, 127.04);
    // Departures 11 m and 111 m from the origin, arrivals 11 m and 111 m from the destination.
    Station d1 = station("d1", new Location(37.5001, 127.02), 3, NOW);
    Station d2 = station("d2", new Location(37.501, 127.02), 5, NOW);
    final Station a1 = station("a1", new Location(37.5201, 127.04), 0, NOW);
    final Station a2 = station("a2", new Location(37.521, 127.04), 20, NOW);
    final PlanBikeRoute planner = new PlanBikeRoute(stations, walking, bicycle, true, Duration.ofSeconds(10),
            RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));

    @BeforeEach void setup() {
        when(walking.configured()).thenReturn(true);
        when(bicycle.configured()).thenReturn(true);
        when(bicycle.supportsBikeRoadPreference()).thenReturn(true);
        when(walking.forRequest()).thenReturn(walking);
        when(bicycle.forRequest()).thenReturn(bicycle);
        when(bicycle.forRequest(any())).thenReturn(bicycle);
        when(walking.attribution()).thenReturn("경로 제공 · Kakao Maps");
        when(bicycle.attribution()).thenReturn("경로 제공 · Kakao Maps");
        when(stations.findNearby(eq(origin), any(Integer.class))).thenAnswer(i -> List.of(d1, d2));
        when(stations.findNearby(eq(destination), any(Integer.class))).thenReturn(List.of(a1, a2));
        when(stations.findByIds(anyList(), eq(origin))).thenAnswer(i -> List.of(d1, d2));
        when(walking.route(any(), any())).thenAnswer(i -> leg("walk", i.getArgument(0), i.getArgument(1)));
        when(bicycle.route(any(), any())).thenAnswer(i -> leg("bicycle", i.getArgument(0), i.getArgument(1)));
        // Every pair takes 100 + 600 + 100 s: ties are broken by walking distance, total distance, then ids.
        first(cell(100, 300), cell(100, 300));
        last(cell(100, 300), cell(100, 300));
        cycle(new RouteMatrix.Cell[][]{{cell(600, 2000), cell(600, 2000)}, {cell(600, 2000), cell(600, 2000)}});
    }

    RouteSearchResult search(RouteSearchOptions options) {
        return planner.search(new RouteSearchCriteria(origin, destination, options, null, null));
    }

    static RouteSearchOptions options(Integer maxWalking, Boolean prefer, boolean exclude, int maxCandidates) {
        return new RouteSearchOptions(1000, maxWalking, prefer, exclude, maxCandidates);
    }

    @Test void fullTiesAreOrderedByDepartureThenArrivalId() {
        var result = search(options(1000, true, true, 5));
        assertThat(result.ranked()).extracting(r -> r.departure().stationId() + ">" + r.arrival().stationId())
                .containsExactly("d1>a1", "d1>a2", "d2>a1", "d2>a2");
    }

    @Test void equalDurationsAreOrderedByWalkingThenTotalDistance() {
        // d2 walks less; among d2's arrivals a2 walks less too; d1>a1 and d1>a2 tie on walking, a1's ride is shorter.
        first(cell(100, 300), cell(100, 200));
        last(cell(100, 300), cell(100, 250));
        cycle(new RouteMatrix.Cell[][]{{cell(600, 1500), cell(600, 2500)}, {cell(600, 2000), cell(600, 2000)}});
        var result = search(options(1000, true, true, 5));
        assertThat(result.ranked()).extracting(r -> r.departure().stationId() + ">" + r.arrival().stationId())
                .containsExactly("d2>a2", "d2>a1", "d1>a2", "d1>a1");
        // d1>a2 (walk 550, total 3050) before d1>a1 (walk 600): walking distance wins over total distance.
    }

    @Test void equalDurationAndWalkingAreOrderedByTotalDistance() {
        cycle(new RouteMatrix.Cell[][]{{cell(600, 2500), cell(600, 1500)}, {cell(600, 2000), cell(600, 2000)}});
        var result = search(options(1000, true, true, 5));
        assertThat(result.ranked()).extracting(r -> r.departure().stationId() + ">" + r.arrival().stationId())
                .containsExactly("d1>a2", "d2>a1", "d2>a2", "d1>a1");
    }

    @Test void durationsAreRoundedPerSegmentBeforeRanking() {
        // d1>a1: 100.4 + 600.4 + 100.4 -> 800 (raw 801.2); d1>a2: 100.4 + 599.6 + 100.4 -> 800 (raw 800.4). Raw sums
        // would pick a2; rounded per segment (decision 8) they tie and the arrival id decides: a1.
        first(cell(100.4, 300), cell(100.4, 300));
        last(cell(100.4, 300), cell(100.4, 300));
        cycle(new RouteMatrix.Cell[][]{{cell(600.4, 2000), cell(599.6, 2000)}, {cell(700, 2000), cell(700, 2000)}});
        assertThat(search(options(1000, true, true, 5)).recommended().arrival().stationId()).isEqualTo("a1");
    }

    @Test void durationWinsOverDistance() {
        cycle(new RouteMatrix.Cell[][]{{cell(500, 9000), cell(600, 100)}, {cell(600, 100), cell(600, 100)}});
        assertThat(search(options(1000, true, true, 5)).recommended().arrival().stationId()).isEqualTo("a1");
    }

    @Test void generatedAtComesFromTheInjectedClock() {
        // Backlog (status 2026-11-19 #7): generatedAt follows the freshness policy clock, not the system clock.
        assertThat(search(options(1000, true, true, 5)).generatedAt()).isEqualTo(NOW);
    }

    @Test void maxCandidatesBoundsTheAlternatives() {
        assertThat(search(options(1000, true, true, 1)).ranked()).hasSize(1);
        assertThat(search(options(1000, true, true, 3)).alternatives()).hasSize(2);
        // Only 4 distinct pairs exist: 5 returns all of them.
        assertThat(search(options(1000, true, true, 5)).ranked()).hasSize(4);
    }

    @Test void sameStationPairIsNeverARoute() {
        // The same station (id d1) is a candidate on both sides.
        when(stations.findNearby(eq(destination), any(Integer.class))).thenReturn(List.of(station("d1", a1.location(), 3, NOW), a2));
        var result = search(options(1000, true, true, 5));
        assertThat(result.ranked()).noneMatch(r -> r.departure().stationId().equals(r.arrival().stationId()));
        assertThat(result.ranked()).hasSize(3);
    }

    @Test void walkingLimitBoundaryIsInclusive() {
        // Walks are 300 + 300 = 600 m for every pair.
        assertThat(search(options(600, true, true, 5)).ranked()).hasSize(4);
        org.mockito.Mockito.clearInvocations(walking);
        assertThatThrownBy(() -> search(options(599, true, true, 5))).isInstanceOfSatisfying(RouteException.class, e -> {
            assertThat(e.code).isEqualTo("NO_CONNECTED_ROUTE");
            assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verify(walking, never()).route(any(), any());
    }

    @Test void walkingLimitRoundsSegmentsBeforeSumming() {
        first(cell(100, 299.5), cell(100, 299.5));
        last(cell(100, 300.4), cell(100, 300.4));
        // round(299.5) + round(300.4) = 300 + 300 = 600.
        assertThat(search(options(600, true, true, 5)).ranked()).hasSize(4);
    }

    @Test void searchRadiusIsPassedAndThe500mPreferenceShrinksWithASmallerRadius() {
        var far = station("far", new Location(37.5040, 127.02), 9, NOW); // about 445 m from the origin
        when(stations.findNearby(eq(origin), any(Integer.class))).thenReturn(List.of(far, d1));
        first(cell(100, 300));
        cycle(new RouteMatrix.Cell[][]{{cell(600, 2000), cell(600, 2000)}});
        when(stations.findByIds(anyList(), eq(origin))).thenReturn(List.of(far, d1));
        planner.search(new RouteSearchCriteria(origin, destination, new RouteSearchOptions(100, 1000, true, true, 5), null, null));
        verify(stations).findNearby(origin, 100);
        verify(stations).findNearby(destination, 100);
        // With radius 100 the preferred band is min(500, 100) = 100 m: only d1 (11 m) is a candidate.
        verify(walking).matrix(List.of(origin), List.of(d1.location()));
    }

    @Test void unsupportedBikeRoadPreferenceIs422BeforeAnyLookup() {
        when(bicycle.supportsBikeRoadPreference()).thenReturn(false);
        assertThatThrownBy(() -> search(options(1000, true, true, 5))).isInstanceOfSatisfying(RouteException.class, e -> {
            assertThat(e.code).isEqualTo("UNSUPPORTED_ROUTE_OPTION");
            assertThat(e.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        });
        verifyNoInteractions(stations);
        // false is honoured by every engine (no preference), null is the legacy engine default.
        assertThat(search(options(1000, false, true, 5)).bikeRoadPreferred()).isFalse();
        verify(bicycle).forRequest(false);
        assertThat(search(options(1000, null, true, 5)).bikeRoadPreferred()).isFalse();
    }

    @Test void preferBikeRoadIsPassedToTheBicycleProvider() {
        var result = search(options(1000, true, true, 5));
        verify(bicycle).forRequest(true);
        assertThat(result.bikeRoadPreferred()).isTrue();
    }

    @Test void failedPairsMakeThePartialResultAndAreNotNoRoute() {
        var failure = new RouteException(HttpStatus.BAD_GATEWAY, "BIKE_ROUTE_PROVIDER_ERROR", "x");
        cycle(new RouteMatrix.Cell[][]{{RouteMatrix.Cell.failed(failure), cell(600, 2000)},
                {RouteMatrix.Cell.noRoute(), RouteMatrix.Cell.noRoute()}});
        var result = search(options(1000, true, true, 5));
        assertThat(result.partial()).isTrue();
        assertThat(result.ranked()).extracting(r -> r.departure().stationId() + ">" + r.arrival().stationId())
                .containsExactly("d1>a2");
    }

    @Test void onlyNoRoutePairsAreNotPartial() {
        cycle(new RouteMatrix.Cell[][]{{RouteMatrix.Cell.noRoute(), cell(600, 2000)},
                {RouteMatrix.Cell.noRoute(), RouteMatrix.Cell.noRoute()}});
        assertThat(search(options(1000, true, true, 5)).partial()).isFalse();
    }

    @Test void noCompleteRouteAnswersTheFirstProviderErrorInPairAndSegmentOrder() {
        var walk = new RouteException(HttpStatus.BAD_GATEWAY, "WALK_ROUTE_PROVIDER_ERROR", "w");
        var bike = new RouteException(HttpStatus.BAD_GATEWAY, "BIKE_ROUTE_PROVIDER_ERROR", "b");
        cycle(new RouteMatrix.Cell[][]{{RouteMatrix.Cell.failed(bike), RouteMatrix.Cell.failed(bike)},
                {RouteMatrix.Cell.failed(bike), RouteMatrix.Cell.failed(bike)}});
        // Every walk routed: pair d1>a1 fails on its bike segment, the bike error is first.
        assertThatThrownBy(() -> search(options(1000, true, true, 5))).isSameAs(bike);
        last(RouteMatrix.Cell.failed(walk), cell(100, 300));
        // Review H1 (changed expectation): before, pair d1>a1 answered its bike error first (segment order walk →
        // bike → walk). Cycling is now only asked for arrivals whose last walk is routed, so d1>a1 has no bike answer
        // and its last-walk failure is its error; pair order still decides (d1>a1 before d1>a2's bike error).
        assertThatThrownBy(() -> search(options(1000, true, true, 5))).isSameAs(walk);
        verify(bicycle).matrix(List.of(d1.location(), d2.location()), List.of(a2.location()));
        first(RouteMatrix.Cell.failed(walk), RouteMatrix.Cell.failed(walk));
        assertThatThrownBy(() -> search(options(1000, true, true, 5))).isSameAs(walk);
    }

    @Test void timeoutCellsWithoutAnyRouteAreGatewayTimeout() {
        var timeout = new RouteException(HttpStatus.GATEWAY_TIMEOUT, "EXTERNAL_API_TIMEOUT", "t");
        cycle(new RouteMatrix.Cell[][]{{RouteMatrix.Cell.failed(timeout), RouteMatrix.Cell.failed(timeout)},
                {RouteMatrix.Cell.failed(timeout), RouteMatrix.Cell.failed(timeout)}});
        assertThatThrownBy(() -> search(options(1000, true, true, 5))).isSameAs(timeout);
    }

    @Test void legFailureOfAnAlternativeIsPartialAndTheRestIsReturned() {
        var failure = new RouteException(HttpStatus.BAD_GATEWAY, "BIKE_ROUTE_PROVIDER_ERROR", "x");
        when(bicycle.route(d1.location(), a2.location())).thenThrow(failure);
        var result = search(options(1000, true, true, 5));
        assertThat(result.partial()).isTrue();
        assertThat(result.ranked()).hasSize(3);
    }

    @Test void vanishedRecommendedDepartureIsReplacedByTheNextRoute() {
        when(stations.findByIds(anyList(), eq(origin))).thenReturn(List.of(station("d1", d1.location(), 0, NOW), d2));
        var result = search(options(1000, true, true, 5));
        assertThat(result.recommended().departure().stationId()).isEqualTo("d2");
        assertThat(result.ranked()).hasSize(2);
        verify(stations).findByIds(List.of("d1", "d2"), origin);
    }

    @Test void noDepartureLeftAfterTheRecheckIsStillStockChanged() {
        when(stations.findByIds(anyList(), eq(origin))).thenReturn(List.of(
                station("d1", d1.location(), 0, NOW), station("d2", d2.location(), 3, NOW.minusSeconds(121))));
        assertThatThrownBy(() -> search(options(1000, true, true, 5))).isInstanceOfSatisfying(RouteException.class,
                e -> assertThat(e.code).isEqualTo("RENTAL_STOCK_CHANGED"));
    }

    @Test void includingStationsWithoutBikesNeverAnswersStockChanged() {
        var stale = station("stale", d2.location(), 4, NOW.minusSeconds(300));
        var empty = station("empty", d1.location(), 0, NOW);
        var gone = station("gone", d1.location(), 9, NOW.minusSeconds(601));
        when(stations.findNearby(eq(origin), any(Integer.class))).thenReturn(List.of(gone, empty, stale));
        when(stations.findByIds(anyList(), eq(origin))).thenReturn(List.of());
        var result = search(options(1000, true, false, 5));
        // UNAVAILABLE stays out (not public); the FRESH 0-bike and STALE departures are routes.
        assertThat(result.ranked()).extracting(r -> r.departure().stationId()).containsOnly("empty", "stale");
        assertThat(result.ranked()).hasSize(4);
    }

    @Test void includingStationsWithoutBikesStill503sWhenNoDepartureIsPublic() {
        when(stations.findNearby(eq(origin), any(Integer.class)))
                .thenReturn(List.of(station("gone", d1.location(), 9, NOW.minusSeconds(601))));
        assertThatThrownBy(() -> search(options(1000, true, false, 5))).isInstanceOfSatisfying(RouteException.class,
                e -> assertThat(e.code).isEqualTo("STATION_STATUS_UNAVAILABLE"));
    }

    @Test void pinnedEmptyDepartureIsAcceptedWhenStationsWithoutBikesAreIncluded() {
        var empty = station("empty", d1.location(), 0, NOW);
        when(stations.findByIds(List.of("empty"), origin)).thenReturn(List.of(empty));
        first(cell(100, 300));
        cycle(new RouteMatrix.Cell[][]{{cell(600, 2000), cell(600, 2000)}});
        var result = planner.search(new RouteSearchCriteria(origin, destination, options(1000, true, false, 5), "empty", null));
        assertThat(result.recommended().departure().stationId()).isEqualTo("empty");
        assertThatThrownBy(() -> planner.search(new RouteSearchCriteria(origin, destination, options(1000, true, true, 5), "empty", null)))
                .isInstanceOfSatisfying(RouteException.class, e -> assertThat(e.code).isEqualTo("PINNED_STATION_EMPTY"));
    }

    @Test void radiusAppearsInTheNoStationMessage() {
        when(stations.findNearby(eq(origin), any(Integer.class))).thenReturn(List.of());
        assertThatThrownBy(() -> planner.search(new RouteSearchCriteria(origin, destination,
                new RouteSearchOptions(300, 1000, true, true, 5), null, null)))
                .isInstanceOfSatisfying(RouteException.class, e -> {
                    assertThat(e.code).isEqualTo("NO_RENTAL_STATION");
                    assertThat(e.getMessage()).contains("300m");
                });
    }

    @Test void optionRangesAreCheckedOnConstruction() {
        new RouteSearchOptions(100, 0, true, true, 1);
        new RouteSearchOptions(5000, 10000, false, false, 5);
        for (Runnable invalid : List.<Runnable>of(() -> new RouteSearchOptions(99, 0, true, true, 1),
                () -> new RouteSearchOptions(5001, 0, true, true, 1), () -> new RouteSearchOptions(100, -1, true, true, 1),
                () -> new RouteSearchOptions(100, 10001, true, true, 1), () -> new RouteSearchOptions(100, 0, true, true, 0),
                () -> new RouteSearchOptions(100, 0, true, true, 6))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RouteSearchProperties(4)).isInstanceOf(IllegalArgumentException.class);
    }

    private void first(RouteMatrix.Cell... cells) {
        when(walking.matrix(eq(List.of(origin)), anyList())).thenReturn(new RouteMatrix(new RouteMatrix.Cell[][]{cells}));
    }

    private void last(RouteMatrix.Cell... cells) {
        var rows = new RouteMatrix.Cell[cells.length][];
        for (int i = 0; i < cells.length; i++) rows[i] = new RouteMatrix.Cell[]{cells[i]};
        when(walking.matrix(anyList(), eq(List.of(destination)))).thenReturn(new RouteMatrix(rows));
    }

    private void cycle(RouteMatrix.Cell[][] cells) {
        when(bicycle.matrix(anyList(), anyList())).thenReturn(new RouteMatrix(cells));
    }

    private static RouteMatrix.Cell cell(double seconds, double meters) { return RouteMatrix.Cell.route(seconds, meters); }

    private static RouteProvider.Leg leg(String mode, Location from, Location to) {
        return new RouteProvider.Leg(mode, 100, 60, List.of(from, to), List.of());
    }

    private static Station station(String id, Location location, Integer bikes, Instant time) {
        return new Station(id, id, "서울", location, 10, bikes, 0, time);
    }
}
