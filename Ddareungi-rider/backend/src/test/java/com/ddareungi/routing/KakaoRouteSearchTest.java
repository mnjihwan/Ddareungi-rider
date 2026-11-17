package com.ddareungi.routing;

import com.ddareungi.global.resilience.ProviderResilience;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A6 search through the real Kakao client, engine adapters and planner (no Spring), against {@link FakeKakaoRouting}:
 * the 15-call budget, legs served from the per-request cache, partial failures, route modes and the deadline.
 */
class KakaoRouteSearchTest {
    static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    static final Location ORIGIN = new Location(37.5000, 127.0200);
    static final Location DESTINATION = new Location(37.5300, 127.0500);
    final Station d1 = station("D1", new Location(37.5010, 127.0200), 3);
    final Station d2 = station("D2", new Location(37.5000, 127.0230), 4);
    final Station d3 = station("D3", new Location(37.4980, 127.0190), 5);
    final Station a1 = station("A1", new Location(37.5290, 127.0500), 0);
    final Station a2 = station("A2", new Location(37.5300, 127.0470), 2);
    final Station a3 = station("A3", new Location(37.5320, 127.0510), 7);
    final StationSearchPort stations = mock(StationSearchPort.class);
    final FakeKakaoRouting kakao = new FakeKakaoRouting();
    final Map<String, Station> latest = new HashMap<>();

    @BeforeEach void setup() {
        when(stations.findNearby(eq(ORIGIN), anyInt())).thenReturn(List.of(d1, d2, d3));
        when(stations.findNearby(eq(DESTINATION), anyInt())).thenReturn(List.of(a1, a2, a3));
        for (var station : List.of(d1, d2, d3)) latest.put(station.stationId(), station);
        when(stations.findByIds(anyList(), eq(ORIGIN))).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return ids.stream().map(latest::get).filter(java.util.Objects::nonNull).toList();
        });
    }

    PlanBikeRoute planner(Duration deadline) { return planner(deadline, "https://dapi.kakao.com"); }

    PlanBikeRoute planner(Duration deadline, String baseUrl) {
        var engine = new KakaoRouteClient(RestClient.builder().requestFactory(kakao), JsonMapper.builder().build(),
                "test-key", ProviderResilience.defaults(), baseUrl);
        return new PlanBikeRoute(stations, new EngineWalkingRouteProvider(engine), new EngineBicycleRouteProvider(engine),
                true, deadline, RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    RouteSearchResult search(RouteSearchOptions options) {
        return planner(Duration.ofSeconds(10)).search(new RouteSearchCriteria(ORIGIN, DESTINATION, options, null, null));
    }

    static RouteSearchOptions options(int maxCandidates) {
        return new RouteSearchOptions(1000, 10000, true, true, maxCandidates);
    }

    @Test void fullSearchStaysWithinFifteenCallsAndReusesEveryLegFromTheRequestCache() {
        var result = search(options(5));
        // 3 + 3 walking + 3 × 3 cycling directions; the 5 × 3 legs of the returned routes cost nothing more.
        assertThat(kakao.calls).hasValue(15);
        assertThat(kakao.count("/v2/routing/walk")).isEqualTo(6);
        assertThat(kakao.count("/v2/routing/bicycle")).isEqualTo(9);
        assertThat(result.ranked()).hasSize(5);
        assertThat(result.partial()).isFalse();
        assertThat(result.bikeRoadPreferred()).isTrue();
        // Ranked by the summed (rounded) segment durations.
        var totals = result.ranked().stream().map(route -> route.legs().stream()
                .mapToLong(leg -> Math.round(leg.durationSeconds())).sum()).toList();
        assertThat(totals).isSorted();
        // Every route is WALK → BIKE → WALK between its two stations.
        for (var route : result.ranked()) {
            assertThat(route.legs()).extracting(RouteProvider.Leg::mode).containsExactly("walk", "bicycle", "walk");
            assertThat(route.legs().get(1).path()).containsExactly(route.departure().location(), route.arrival().location());
        }
        // The recommended route is the global minimum of the 9 pairs under the fake's speeds.
        var best = List.of(d1, d2, d3).stream().flatMap(d -> List.of(a1, a2, a3).stream().map(a -> List.of(d, a)))
                .min(java.util.Comparator.comparingLong(pair -> total(pair.get(0), pair.get(1)))).orElseThrow();
        assertThat(result.recommended().departure().stationId()).isEqualTo(best.get(0).stationId());
        assertThat(result.recommended().arrival().stationId()).isEqualTo(best.get(1).stationId());
    }

    @Test void maxCandidatesOneStillComparesAllPairsWithinTheSameBudget() {
        var result = search(options(1));
        assertThat(result.ranked()).hasSize(1);
        assertThat(kakao.calls).hasValue(15);
    }

    @Test void oneFailedCyclingPairIsAPartialResultNotANoRoute() {
        kakao.failing = uri -> uri.getPath().endsWith("/bicycle") && FakeKakaoRouting.between(uri, d1.location(), a1.location());
        var result = search(options(5));
        assertThat(result.partial()).isTrue();
        assertThat(result.ranked()).hasSize(5).noneMatch(route ->
                route.departure().stationId().equals("D1") && route.arrival().stationId().equals("A1"));
        // The failed pair is not retried later (429 is never retried) and the budget holds.
        assertThat(kakao.calls.get()).isLessThanOrEqualTo(15);
    }

    @Test void everyCyclingCallFailingIsTheBikeProviderErrorWithinTheBudget() {
        kakao.failing = uri -> uri.getPath().endsWith("/bicycle");
        assertThatThrownBy(() -> search(options(5))).isInstanceOfSatisfying(RouteException.class, error -> {
            assertThat(error.code).isEqualTo("BIKE_ROUTE_PROVIDER_ERROR");
            assertThat(error.status).isEqualTo(HttpStatus.BAD_GATEWAY);
        });
        assertThat(kakao.calls.get()).isLessThanOrEqualTo(15);
    }

    @Test void walkingFailureComesFirstInSegmentOrder() {
        kakao.failing = uri -> true;
        assertThatThrownBy(() -> search(options(5))).isInstanceOfSatisfying(RouteException.class,
                error -> assertThat(error.code).isEqualTo("WALK_ROUTE_PROVIDER_ERROR"));
        assertThat(kakao.calls.get()).isLessThanOrEqualTo(15);
    }

    @Test void preferBikeRoadSelectsTheKakaoRouteMode() {
        search(options(5));
        assertThat(kakao.count("route_mode=BIKE_ONLY")).isEqualTo(9);
        assertThat(kakao.count("route_mode=SHORTEST")).isZero();

        kakao.uris.clear();
        kakao.calls.set(0);
        var shortest = search(new RouteSearchOptions(1000, 10000, false, true, 5));
        assertThat(kakao.count("route_mode=SHORTEST")).isEqualTo(9);
        assertThat(kakao.count("route_mode=BIKE_ONLY")).isZero();
        assertThat(kakao.calls).hasValue(15);
        assertThat(shortest.bikeRoadPreferred()).isFalse();
    }

    @Test void walkingLimitUsesTheProviderRouteDistances() {
        // Only pairs whose two walks (straight line × 1.3 here) fit in the limit remain.
        int limit = 400;
        var result = search(new RouteSearchOptions(1000, limit, true, true, 5));
        for (var route : result.ranked()) {
            long walking = Math.round(route.legs().get(0).distanceMeters()) + Math.round(route.legs().get(2).distanceMeters());
            assertThat(walking).isLessThanOrEqualTo(limit);
        }
        long expected = List.of(d1, d2, d3).stream().flatMap(d -> List.of(a1, a2, a3).stream()
                .filter(a -> Math.round(FakeKakaoRouting.distance(ORIGIN, d.location()))
                        + Math.round(FakeKakaoRouting.distance(a.location(), DESTINATION)) <= limit)).count();
        assertThat(result.ranked()).hasSize((int) Math.min(5, expected));

        assertThatThrownBy(() -> search(new RouteSearchOptions(1000, 0, true, true, 5)))
                .isInstanceOfSatisfying(RouteException.class, error -> {
                    assertThat(error.code).isEqualTo("NO_CONNECTED_ROUTE");
                    assertThat(error.status).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    @Test void vanishedRecommendedDepartureIsReplacedByTheNextRouteWithoutProviderCalls() {
        var first = search(options(5));
        String recommended = first.recommended().departure().stationId();
        kakao.calls.set(0);
        latest.put(recommended, station(recommended, latest.get(recommended).location(), 0));
        var second = search(options(5));
        assertThat(second.recommended().departure().stationId()).isNotEqualTo(recommended);
        assertThat(second.ranked()).noneMatch(route -> route.departure().stationId().equals(recommended));
        assertThat(kakao.calls).hasValue(15);
    }

    @Test void baseUrlIsConfigurable() {
        planner(Duration.ofSeconds(10), "http://127.0.0.1:18999")
                .search(new RouteSearchCriteria(ORIGIN, DESTINATION, options(1), null, null));
        assertThat(kakao.uris).isNotEmpty().allMatch(uri -> uri.toString().startsWith("http://127.0.0.1:18999/v2/routing/"));
    }

    @Test void slowProviderWithinTheDeadlineStillAnswersAndParallelCallsAreCapped() {
        kakao.delayMillis = 100;
        long started = System.nanoTime();
        var result = search(options(5));
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(result.ranked()).hasSize(5);
        // Sequential matrices: 3 walks (parallel) + 3 walks (parallel) + 9 rides (6 at a time) = 4 rounds of 100 ms.
        assertThat(elapsed).isGreaterThanOrEqualTo(400);
        assertThat(kakao.calls).hasValue(15);
    }

    @Test void slowProviderPastTheDeadlineIsGatewayTimeout() {
        kakao.delayMillis = 3_000;
        long started = System.nanoTime();
        assertThatThrownBy(() -> planner(Duration.ofMillis(300))
                .search(new RouteSearchCriteria(ORIGIN, DESTINATION, options(5), null, null)))
                .isInstanceOfSatisfying(RouteException.class, error -> {
                    assertThat(error.code).isEqualTo("EXTERNAL_API_TIMEOUT");
                    assertThat(error.status).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    private long total(Station departure, Station arrival) {
        return Math.round(FakeKakaoRouting.seconds(ORIGIN, departure.location(), true))
                + Math.round(FakeKakaoRouting.seconds(departure.location(), arrival.location(), false))
                + Math.round(FakeKakaoRouting.seconds(arrival.location(), DESTINATION, true));
    }

    private static Station station(String id, Location location, int bikes) {
        return new Station(id, id, "서울", location, 10, bikes, 0, NOW);
    }
}
