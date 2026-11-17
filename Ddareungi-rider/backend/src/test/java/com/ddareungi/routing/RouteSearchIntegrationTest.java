package com.ddareungi.routing;

import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A6 {@code POST /api/v1/routes/search} through the real controller, planner, adapters and exception handler with a
 * mocked engine: response contract, partial results, 422 option, validation and the 16 KiB body limit (413).
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        "routing.enabled=true",
        // Many route requests from one client: the routes bucket is covered by RateLimitIntegrationTest.
        "ratelimit.enabled=false"
})
class RouteSearchIntegrationTest {
    static final Location ORIGIN = new Location(37.4979, 127.0276);
    static final Location DESTINATION = new Location(37.5045, 127.0248);
    static final String ENDPOINTS = "\"origin\":{\"latitude\":37.4979,\"longitude\":127.0276},"
            + "\"destination\":{\"latitude\":37.5045,\"longitude\":127.0248}";
    final Station departure = station("D", new Location(37.4980, 127.0277), 5);
    final Station other = station("E", new Location(37.4985, 127.0277), 2);
    final Station arrival = station("A", new Location(37.5044, 127.0247), 3);

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    @MockitoBean RouteProvider engine;
    @MockitoBean StationSearchPort stations;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        when(stations.findNearby(eq(ORIGIN), any(Integer.class))).thenReturn(List.of(departure));
        when(stations.findNearby(eq(DESTINATION), any(Integer.class))).thenReturn(List.of(arrival));
        when(stations.findByIds(anyList(), eq(ORIGIN))).thenReturn(List.of(departure, other));
        when(engine.configured()).thenReturn(true);
        when(engine.supportsBikeRoadPreference()).thenReturn(true);
        when(engine.forRequest()).thenReturn(engine);
        when(engine.attribution()).thenReturn("경로 제공 · Kakao Maps");
        when(engine.matrix(any(), anyList(), anyList())).thenAnswer(invocation -> {
            List<?> from = invocation.getArgument(1), to = invocation.getArgument(2);
            var durations = new Double[from.size()][to.size()];
            for (var row : durations) java.util.Arrays.fill(row, 60.0);
            return RouteMatrix.of(durations, durations);
        });
        when(engine.directions(any(), any(), any())).thenAnswer(invocation -> new RouteProvider.Leg(
                invocation.getArgument(0).equals(RouteProvider.WALKING) ? "walk" : "bicycle", 80.4, 60.6,
                List.of(invocation.getArgument(1), invocation.getArgument(2)),
                List.of(new RouteProvider.Step("직진", 80.4, 60.6))));
    }

    private ResultActions search(String body) throws Exception {
        return mvc.perform(post("/api/v1/routes/search").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(RequestIdFilter.HEADER, "route-search"));
    }

    @Test void answersTheSpecShape() throws Exception {
        search("{" + ENDPOINTS + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.meta.requestId").value("route-search"))
                .andExpect(jsonPath("$.meta.partial").doesNotExist())
                .andExpect(jsonPath("$.data.recommended.routeId").value(matchesPattern("route-[0-9a-f]{12}")))
                .andExpect(jsonPath("$.data.recommended.totalDistanceMeters").value(240))
                .andExpect(jsonPath("$.data.recommended.estimatedDurationSeconds").value(183))
                .andExpect(jsonPath("$.data.recommended.walkingDistanceMeters").value(160))
                .andExpect(jsonPath("$.data.recommended.bikeDistanceMeters").value(80))
                .andExpect(jsonPath("$.data.recommended.originStation.stationId").value("D"))
                .andExpect(jsonPath("$.data.recommended.originStation.parkingBikeTotCnt").value(5))
                .andExpect(jsonPath("$.data.recommended.originStation.realtimeStatus").value("FRESH"))
                .andExpect(jsonPath("$.data.recommended.originStation.realtimeUpdatedAt").isString())
                .andExpect(jsonPath("$.data.recommended.originStation.realtimeAgeSeconds").isNumber())
                .andExpect(jsonPath("$.data.recommended.destinationStation.stationId").value("A"))
                .andExpect(jsonPath("$.data.recommended.segments.length()").value(3))
                .andExpect(jsonPath("$.data.recommended.segments[0].type").value("WALK"))
                .andExpect(jsonPath("$.data.recommended.segments[0].bikeRoadPreferred").doesNotExist())
                .andExpect(jsonPath("$.data.recommended.segments[1].type").value("BIKE"))
                .andExpect(jsonPath("$.data.recommended.segments[1].bikeRoadPreferred").value(true))
                .andExpect(jsonPath("$.data.recommended.segments[1].encodedPolyline").isString())
                .andExpect(jsonPath("$.data.recommended.segments[1].steps[0].instruction").value("직진"))
                .andExpect(jsonPath("$.data.recommended.rentalAvailability").value("AVAILABLE_AT_FETCH"))
                .andExpect(jsonPath("$.data.recommended.returnAvailability").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.alternatives").isArray())
                .andExpect(jsonPath("$.data.alternatives.length()").value(0))
                .andExpect(jsonPath("$.data.attribution").value("경로 제공 · Kakao Maps"))
                // Phase 3 decision 2: no origin/destination echo, no legacy names.
                .andExpect(jsonPath("$.data.origin").doesNotExist())
                .andExpect(jsonPath("$.data.recommended.legs").doesNotExist())
                .andExpect(jsonPath("$.data.recommended.departureStation").doesNotExist());
    }

    @Test void alternativesAndPartialFailure() throws Exception {
        when(stations.findNearby(eq(ORIGIN), any(Integer.class))).thenReturn(List.of(departure, other));
        when(engine.matrix(eq(RouteProvider.CYCLING), anyList(), anyList())).thenReturn(new RouteMatrix(new RouteMatrix.Cell[][]{
                {RouteMatrix.Cell.route(60, 60)},
                {RouteMatrix.Cell.failed(new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_LIMIT", "한도"))}}));
        search("{" + ENDPOINTS + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.partial").value(true))
                .andExpect(jsonPath("$.meta.warnings[0].code").value("PROVIDER_PARTIAL_FAILURE"))
                .andExpect(jsonPath("$.data.recommended.originStation.stationId").value("D"))
                .andExpect(jsonPath("$.data.alternatives.length()").value(0));

        when(engine.matrix(eq(RouteProvider.CYCLING), anyList(), anyList())).thenReturn(RouteMatrix.of(
                new Double[][]{{60.0}, {50.0}}, new Double[][]{{60.0}, {60.0}}));
        search("{" + ENDPOINTS + ",\"options\":{\"maxCandidates\":2}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended.originStation.stationId").value("E"))
                .andExpect(jsonPath("$.data.alternatives.length()").value(1))
                .andExpect(jsonPath("$.data.alternatives[0].originStation.stationId").value("D"));
        search("{" + ENDPOINTS + ",\"options\":{\"maxCandidates\":1}}")
                .andExpect(jsonPath("$.data.alternatives.length()").value(0));
    }

    @Test void everyPairFailingIsTheSegmentProviderError() throws Exception {
        when(engine.matrix(eq(RouteProvider.CYCLING), anyList(), anyList())).thenReturn(new RouteMatrix(new RouteMatrix.Cell[][]{
                {RouteMatrix.Cell.failed(new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_AUTH", "인증"))}}));
        search("{" + ENDPOINTS + "}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("BIKE_ROUTE_PROVIDER_ERROR"))
                .andExpect(content().string(not(containsString("ROUTING_"))));
    }

    @Test void preferBikeRoadOnAnEngineWithoutItIs422AndFalseWorks() throws Exception {
        when(engine.supportsBikeRoadPreference()).thenReturn(false);
        search("{" + ENDPOINTS + "}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_ROUTE_OPTION"));
        search("{" + ENDPOINTS + ",\"options\":{\"preferBikeRoad\":false}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommended.segments[1].bikeRoadPreferred").value(false));
        // The legacy /plan adapter keeps the engine default and never answers 422.
        mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content("{" + ENDPOINTS + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.departureStation.stationId").value("D"))
                .andExpect(jsonPath("$.data.legs.length()").value(3));
    }

    @Test void walkingLimitExcludingEveryRouteIsRouteNotFound() throws Exception {
        search("{" + ENDPOINTS + ",\"options\":{\"maxWalkingDistanceMeters\":0}}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
    }

    @Test void optionBoundariesAndNulls() throws Exception {
        for (String options : List.of("{\"stationSearchRadiusMeters\":100,\"maxWalkingDistanceMeters\":10000,\"maxCandidates\":1}",
                "{\"stationSearchRadiusMeters\":5000,\"maxWalkingDistanceMeters\":1000,\"maxCandidates\":5}",
                "{}", "{\"excludeStationsWithoutBikes\":false}")) {
            search("{" + ENDPOINTS + ",\"options\":" + options + "}").andExpect(status().isOk());
        }
        for (String field : List.of("\"stationSearchRadiusMeters\":99", "\"stationSearchRadiusMeters\":5001",
                "\"maxWalkingDistanceMeters\":-1", "\"maxWalkingDistanceMeters\":10001",
                "\"maxCandidates\":0", "\"maxCandidates\":6")) {
            String name = field.substring(1, field.indexOf('"', 1));
            search("{" + ENDPOINTS + ",\"options\":{" + field + "}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.error.details[0].field").value(name));
        }
        for (String invalid : List.of("\"options\":null", "\"options\":{\"preferBikeRoad\":null}",
                "\"options\":{\"maxCandidates\":null}", "\"options\":{\"maxCandidates\":\"many\"}",
                "\"options\":{\"excludeStationsWithoutBikes\":\"yes\"}")) {
            search("{" + ENDPOINTS + "," + invalid + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        }
        search("{\"destination\":{\"latitude\":37.5,\"longitude\":127}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
        search("{\"origin\":{\"latitude\":91,\"longitude\":127},\"destination\":{\"latitude\":37.5,\"longitude\":127}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_COORDINATE"));
    }

    @Test void radiusOptionReachesTheStationLookup() throws Exception {
        search("{" + ENDPOINTS + ",\"options\":{\"stationSearchRadiusMeters\":300}}").andExpect(status().isOk());
        org.mockito.Mockito.verify(stations).findNearby(ORIGIN, 300);
        org.mockito.Mockito.verify(stations).findNearby(DESTINATION, 300);
    }

    @Test void bodiesOverSixteenKibAre413OnEveryJsonPost() throws Exception {
        String exact = padded("{" + ENDPOINTS + "}", 16 * 1024);
        search(exact).andExpect(status().isOk());
        String over = padded("{" + ENDPOINTS + "}", 16 * 1024 + 1);
        search(over)
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.meta.requestId").value("route-search"));
        mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(over))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
        mvc.perform(post("/api/v1/stations/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(padded("{\"stationIds\":[\"ST-001\"]}", 20_000)))
                .andExpect(status().is(413));
    }

    /** JSON padded with trailing spaces to exactly {@code size} bytes (ASCII body). */
    private static String padded(String json, int size) {
        return json + " ".repeat(size - json.length());
    }

    private static Station station(String id, Location location, int bikes) {
        return new Station(id, id + " 대여소", "주소", location, 10, bikes, 50, Instant.now());
    }
}
