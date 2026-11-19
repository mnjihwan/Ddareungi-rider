package com.ddareungi.routing;

import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Review L1: an engine provider failure on a walking or cycling leg reaches {@code POST /api/v1/routes/plan} as
 * 502 with the segment-specific code, through the real adapters, planner, controller and exception handler.
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        "routing.enabled=true"
})
class RoutePlanProviderErrorIntegrationTest {
    static final Location ORIGIN = new Location(37.4979, 127.0276);
    static final Location DESTINATION = new Location(37.5045, 127.0248);
    static final String BODY = "{\"origin\":{\"latitude\":37.4979,\"longitude\":127.0276},"
            + "\"destination\":{\"latitude\":37.5045,\"longitude\":127.0248}}";

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    @MockitoBean RouteProvider engine;
    @MockitoBean StationSearchPort stations;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
        var departure = station("D", new Location(37.4980, 127.0277), 5);
        var arrival = station("A", new Location(37.5044, 127.0247), 3);
        when(stations.findNearby(eq(ORIGIN), eq(1000))).thenReturn(List.of(departure));
        when(stations.findNearby(eq(DESTINATION), eq(1000))).thenReturn(List.of(arrival));
        when(engine.configured()).thenReturn(true);
        when(engine.forRequest()).thenReturn(engine);
        when(engine.attribution()).thenReturn("경로 제공 · Kakao Maps");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTING_UNAVAILABLE", "ROUTING_LIMIT", "ROUTING_AUTH"})
    void walkingLegProviderFailureIsWalkRouteProviderError(String engineCode) throws Exception {
        // A6 contract change: the planner reads matrix(..) (durations + distances + per-pair failures), not durations(..).
        when(engine.matrix(eq(EngineWalkingRouteProvider.PROFILE), anyList(), anyList()))
                .thenThrow(new RouteException(HttpStatus.BAD_GATEWAY, engineCode, "도보 경로 공급자 오류"));

        failure(plan(), "WALK_ROUTE_PROVIDER_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTING_UNAVAILABLE", "ROUTING_LIMIT", "ROUTING_AUTH"})
    void cyclingLegProviderFailureIsBikeRouteProviderError(String engineCode) throws Exception {
        // A6 contract change: the planner reads matrix(..) (durations + distances + per-pair failures), not durations(..).
        when(engine.matrix(eq(EngineWalkingRouteProvider.PROFILE), anyList(), anyList()))
                .thenReturn(RouteMatrix.of(new Double[][]{{60.0}}, new Double[][]{{60.0}}));
        // A6 contract change: the planner reads matrix(..) (durations + distances + per-pair failures), not durations(..).
        when(engine.matrix(eq(EngineBicycleRouteProvider.PROFILE), anyList(), anyList()))
                .thenThrow(new RouteException(HttpStatus.BAD_GATEWAY, engineCode, "자전거 경로 공급자 오류"));

        failure(plan(), "BIKE_ROUTE_PROVIDER_ERROR");
    }

    @Test
    void walkingDirectionsFailureAfterMatrixIsWalkRouteProviderError() throws Exception {
        // A6 contract change: the planner reads matrix(..) (durations + distances + per-pair failures), not durations(..).
        when(engine.matrix(any(), anyList(), anyList())).thenReturn(RouteMatrix.of(new Double[][]{{60.0}}, new Double[][]{{60.0}}));
        when(engine.directions(eq(EngineWalkingRouteProvider.PROFILE), any(), any()))
                .thenThrow(new RouteException(HttpStatus.TOO_MANY_REQUESTS, "ROUTING_LIMIT", "도보 경로 공급자 오류"));

        failure(plan(), "WALK_ROUTE_PROVIDER_ERROR");
    }

    private ResultActions plan() throws Exception {
        return mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header(RequestIdFilter.HEADER, "provider-error"));
    }

    private static void failure(ResultActions result, String code) throws Exception {
        result.andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").value("provider-error"))
                .andExpect(jsonPath("$.timestamp").isString())
                // The internal engine code never reaches the client.
                .andExpect(content().string(not(containsString("ROUTING_"))));
    }

    private static Station station(String id, Location location, int bikes) {
        return new Station(id, id + " 대여소", "주소", location, 10, bikes, 50, Instant.now());
    }
}
