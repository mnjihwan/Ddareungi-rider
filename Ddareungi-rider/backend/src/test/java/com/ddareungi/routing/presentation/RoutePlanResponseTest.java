package com.ddareungi.routing.presentation;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.routing.PlanBikeRoute;
import com.ddareungi.routing.RouteProvider;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Review M8: route response stations are classified with the configured freshness policy, not a fixed default. */
class RoutePlanResponseTest {
    static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    static final Location ORIGIN = new Location(37.50, 127.02);
    static final Location DESTINATION = new Location(37.52, 127.04);

    private static PlanBikeRoute.Plan plan(long departureAge, long arrivalAge) {
        var departure = new Station("d", "d", "서울", new Location(37.5001, 127.02), 10, 3, 0, NOW.minusSeconds(departureAge));
        var arrival = new Station("a", "a", "서울", new Location(37.5201, 127.04), 10, 0, 0, NOW.minusSeconds(arrivalAge));
        return new PlanBikeRoute.Plan(ORIGIN, DESTINATION, departure, arrival,
                List.of(new RouteProvider.Leg("walk", 1, 1, List.of(), List.of())), 1, 1, NOW, "attribution");
    }

    private static StationViewMapper mapper(long freshSeconds, long maxStaleSeconds) {
        return new StationViewMapper(new RealtimeFreshnessPolicy(Clock.fixed(NOW, ZoneOffset.UTC), freshSeconds, maxStaleSeconds));
    }

    @Test
    void defaultThresholdsClassify100sAsFresh() {
        var response = RoutePlanResponse.from(plan(10, 100), mapper(120, 600));
        assertThat(response.departureStation().realtimeStatus()).isEqualTo(RealtimeFreshness.FRESH);
        assertThat(response.arrivalStation().realtimeStatus()).isEqualTo(RealtimeFreshness.FRESH);
        assertThat(response.arrivalStation().realtimeAgeSeconds()).isEqualTo(100L);
    }

    @Test
    void configuredThresholdsAreFollowed() {
        // realtime.freshness.fresh-seconds=60: a 100 s-old arrival is STALE (the former fixed default said FRESH).
        var tight = RoutePlanResponse.from(plan(10, 100), mapper(60, 600));
        assertThat(tight.departureStation().realtimeStatus()).isEqualTo(RealtimeFreshness.FRESH);
        assertThat(tight.arrivalStation().realtimeStatus()).isEqualTo(RealtimeFreshness.STALE);

        // fresh-seconds=300: a 200 s-old arrival is FRESH (the former fixed default said STALE).
        var lenient = RoutePlanResponse.from(plan(10, 200), mapper(300, 900));
        assertThat(lenient.arrivalStation().realtimeStatus()).isEqualTo(RealtimeFreshness.FRESH);
        assertThat(lenient.arrivalStation().realtimeAgeSeconds()).isEqualTo(200L);
    }
}
