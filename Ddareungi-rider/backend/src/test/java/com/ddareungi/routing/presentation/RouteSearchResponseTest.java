package com.ddareungi.routing.presentation;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.routing.RouteProvider;
import com.ddareungi.routing.RouteSearchResult;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.ddareungi.routing.presentation.RouteSearchResponse.RentalAvailability.AVAILABLE_AT_FETCH;
import static com.ddareungi.routing.presentation.RouteSearchResponse.RentalAvailability.UNAVAILABLE_AT_FETCH;
import static com.ddareungi.routing.presentation.RouteSearchResponse.RentalAvailability.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

/** A6 response mapping: rounding and totals, segments, polylines, availability and route ids. */
class RouteSearchResponseTest {
    static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    static final Location ORIGIN = new Location(37.50, 127.02), DESTINATION = new Location(37.52, 127.04);
    static final Location D = new Location(37.5001, 127.02), A = new Location(37.5201, 127.04);
    final StationViewMapper mapper = new StationViewMapper(new RealtimeFreshnessPolicy(Clock.fixed(NOW, ZoneOffset.UTC), 120, 600));

    static Station station(String id, Location location, Integer bikes, Instant at) {
        return new Station(id, id, "서울", location, 10, bikes, 0, at);
    }

    static RouteSearchResult.Route route(Station departure) {
        return new RouteSearchResult.Route(departure, station("a", A, 2, NOW), List.of(
                new RouteProvider.Leg("walk", 120.4, 100.5, List.of(ORIGIN, D), List.of(new RouteProvider.Step("직진", 120.4, 100.5))),
                new RouteProvider.Leg("bicycle", 2999.6, 600.4, List.of(D, new Location(37.51, 127.03), A), List.of()),
                new RouteProvider.Leg("walk", 0, 0, List.of(A), List.of())));
    }

    RouteSearchResponse map(boolean bikeRoadPreferred, RouteSearchResult.Route... routes) {
        return RouteSearchResponse.from(new RouteSearchResult(ORIGIN, DESTINATION, List.of(routes), false,
                bikeRoadPreferred, NOW, "경로 제공 · Kakao Maps"), mapper);
    }

    @Test void segmentsAreRoundedAndTotalsAreTheirSums() {
        var recommended = map(true, route(station("d", D, 3, NOW))).recommended();
        assertThat(recommended.segments()).extracting(RouteSearchResponse.SegmentResponse::type).containsExactly(
                RouteSearchResponse.SegmentType.WALK, RouteSearchResponse.SegmentType.BIKE, RouteSearchResponse.SegmentType.WALK);
        assertThat(recommended.segments()).extracting(RouteSearchResponse.SegmentResponse::distanceMeters).containsExactly(120L, 3000L, 0L);
        assertThat(recommended.segments()).extracting(RouteSearchResponse.SegmentResponse::durationSeconds).containsExactly(101L, 600L, 0L);
        assertThat(recommended.walkingDistanceMeters()).isEqualTo(120);
        assertThat(recommended.bikeDistanceMeters()).isEqualTo(3000);
        assertThat(recommended.totalDistanceMeters()).isEqualTo(3120);
        assertThat(recommended.estimatedDurationSeconds()).isEqualTo(701);
        assertThat(recommended.returnAvailability()).isEqualTo(RouteSearchResponse.ReturnAvailability.UNKNOWN);
        assertThat(recommended.segments().getFirst().steps()).hasSize(1);
    }

    @Test void polylinesDecodeToTheLegPathAndAZeroWalkKeepsTwoPoints() {
        var segments = map(true, route(station("d", D, 3, NOW))).recommended().segments();
        assertThat(EncodedPolyline.decode(segments.get(1).encodedPolyline())).containsExactly(D, new Location(37.51, 127.03), A);
        assertThat(EncodedPolyline.decode(segments.get(2).encodedPolyline())).containsExactly(A, A);
    }

    @Test void bikeRoadPreferredIsOnlyOnTheBikeSegment() {
        var preferred = map(true, route(station("d", D, 3, NOW))).recommended().segments();
        assertThat(preferred).extracting(RouteSearchResponse.SegmentResponse::bikeRoadPreferred).containsExactly(null, true, null);
        var plain = map(false, route(station("d", D, 3, NOW))).recommended().segments();
        assertThat(plain.get(1).bikeRoadPreferred()).isFalse();
    }

    @Test void rentalAvailabilityFollowsTheStationViewAtOneInstant() {
        var response = map(true, route(station("fresh", D, 3, NOW.minusSeconds(120))), route(station("empty", D, 0, NOW)),
                route(station("stale", D, 5, NOW.minusSeconds(121))));
        assertThat(response.recommended().rentalAvailability()).isEqualTo(AVAILABLE_AT_FETCH);
        assertThat(response.recommended().originStation().realtimeStatus()).isEqualTo(RealtimeFreshness.FRESH);
        assertThat(response.alternatives()).extracting(RouteSearchResponse.RouteResponse::rentalAvailability)
                .containsExactly(UNAVAILABLE_AT_FETCH, UNKNOWN);
        assertThat(response.alternatives().get(1).originStation().realtimeStatus()).isEqualTo(RealtimeFreshness.STALE);
        assertThat(response.attribution()).isEqualTo("경로 제공 · Kakao Maps");
    }

    @Test void routeIdsAreRouteDashTwelveHexAndUnique() {
        var response = map(true, route(station("d", D, 3, NOW)), route(station("e", D, 3, NOW)));
        assertThat(response.recommended().routeId()).matches("route-[0-9a-f]{12}");
        assertThat(response.alternatives().getFirst().routeId()).matches("route-[0-9a-f]{12}")
                .isNotEqualTo(response.recommended().routeId());
    }
}
