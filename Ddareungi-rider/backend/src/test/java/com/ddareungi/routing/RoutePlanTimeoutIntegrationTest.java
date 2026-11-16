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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** E4/D13: provider timeouts and the plan deadline reach the client as 504 EXTERNAL_API_TIMEOUT (unchanged handler). */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        "routing.enabled=true",
        "provider.http.route.total-timeout=300ms"
})
class RoutePlanTimeoutIntegrationTest {
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
        when(stations.findNearby(eq(ORIGIN), eq(1000))).thenReturn(List.of(station("D", new Location(37.4980, 127.0277))));
        when(stations.findNearby(eq(DESTINATION), eq(1000))).thenReturn(List.of(station("A", new Location(37.5044, 127.0247))));
        when(engine.configured()).thenReturn(true);
        when(engine.forRequest()).thenReturn(engine);
        when(engine.attribution()).thenReturn("경로 제공 · Kakao Maps");
    }

    @Test void providerTimeoutIsGatewayTimeout() throws Exception {
        when(engine.durations(any(), anyList(), anyList()))
                .thenThrow(new RouteException(HttpStatus.GATEWAY_TIMEOUT, "EXTERNAL_API_TIMEOUT", "경로 응답 지연"));
        timeout(plan());
    }

    @Test void planDeadlineIsGatewayTimeout() throws Exception {
        when(engine.durations(any(), anyList(), anyList())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return new Double[][]{{60.0}};
        });
        timeout(plan());
    }

    private ResultActions plan() throws Exception {
        return mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header(RequestIdFilter.HEADER, "route-timeout"));
    }

    private static void timeout(ResultActions result) throws Exception {
        result.andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_API_TIMEOUT"))
                .andExpect(jsonPath("$.meta.requestId").value("route-timeout"));
    }

    private static Station station(String id, Location location) {
        return new Station(id, id + " 대여소", "주소", location, 10, 5, 50, Instant.now());
    }
}
