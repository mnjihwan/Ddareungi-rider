package com.ddareungi.station.presentation;

import com.ddareungi.global.exception.GlobalExceptionHandler;
import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.application.FindNearbyStationsUseCase;
import com.ddareungi.station.application.StationMapProperties;
import com.ddareungi.station.application.StationMapService;
import com.ddareungi.station.application.StationQueryService;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R4 on the wire: {@code /stations/clusters} contract and validation, {@code /stations/viewport} cap. */
class StationMapEndpointsTest {
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private StationSearchPort port;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        port = mock(StationSearchPort.class);
        var mapper = new StationViewMapper(RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));
        var clock = new DefaultListableBeanFactory();
        clock.registerSingleton("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new StationController(new FindNearbyStationsUseCase(port), mapper,
                        new StationQueryService(port), new StationMapService(port, mapper, StationMapProperties.defaults())))
                .setControllerAdvice(new GlobalExceptionHandler(clock.getBeanProvider(Clock.class)))
                .addFilters(new RequestIdFilter()).build();
    }

    private static ResultActions failure(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").isString());
    }

    private static MockHttpServletRequestBuilder clusters(String south, String north, String west, String east, String level) {
        var request = get("/api/v1/stations/clusters");
        if (south != null) request.param("south", south);
        if (north != null) request.param("north", north);
        if (west != null) request.param("west", west);
        if (east != null) request.param("east", east);
        if (level != null) request.param("level", level);
        return request;
    }

    private static StationCandidate candidate(String id, double lat, double lon, RealtimeFreshness status, Integer bikes) {
        boolean servable = status.servable();
        return new StationCandidate(new Station(id, id, "서울", new Location(lat, lon), 10, servable ? bikes : null, 0,
                servable ? NOW : null), status, servable ? 0L : null);
    }

    @Test
    void clustersReturnTheDocumentedShape() throws Exception {
        when(port.findCandidatesInBounds(new StationBounds(37.49, 37.51, 127.02, 127.04))).thenReturn(List.of(
                candidate("a", 37.4979, 127.0276, RealtimeFreshness.FRESH, 7),
                candidate("b", 37.4981, 127.0278, RealtimeFreshness.FRESH, 0),
                candidate("c", 37.5006, 127.0364, RealtimeFreshness.FRESH, 2)));

        mvc.perform(clusters("37.49", "37.51", "127.02", "127.04", "6").header(RequestIdFilter.HEADER, "cl-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.meta.requestId").value("cl-1"))
                .andExpect(jsonPath("$.meta.partial").doesNotExist())
                .andExpect(jsonPath("$.meta.warnings").doesNotExist())
                .andExpect(jsonPath("$.data.level").value(6))
                .andExpect(jsonPath("$.data.cellDegrees").value(0.005))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].latitude").value(closeTo(37.498, 1e-9)))
                .andExpect(jsonPath("$.data.items[0].longitude").value(closeTo(127.0277, 1e-9)))
                .andExpect(jsonPath("$.data.items[0].stationCount").value(2))
                .andExpect(jsonPath("$.data.items[0].parkingBikeTotCnt").value(7))
                .andExpect(jsonPath("$.data.items[0].freshCount").value(2))
                .andExpect(jsonPath("$.data.items[1].stationCount").value(1))
                .andExpect(jsonPath("$.data.items[1].parkingBikeTotCnt").value(2))
                .andExpect(jsonPath("$.data.items[0].stationId").doesNotExist());
    }

    @Test
    void clustersFollowTheA3PartialRule() throws Exception {
        when(port.findCandidatesInBounds(any())).thenReturn(List.of(
                candidate("s", 37.4979, 127.0276, RealtimeFreshness.STALE, 4),
                candidate("u", 37.4980, 127.0277, RealtimeFreshness.UNAVAILABLE, null)));

        mvc.perform(clusters("37.49", "37.51", "127.02", "127.04", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.partial").value(true))
                .andExpect(jsonPath("$.meta.warnings[*].code").value(contains("STALE_DATA", "REALTIME_DATA_UNAVAILABLE")))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].stationCount").value(1))
                .andExpect(jsonPath("$.data.items[0].parkingBikeTotCnt").value(4))
                .andExpect(jsonPath("$.data.items[0].freshCount").value(0));
    }

    @Test
    void everyLevelFromFiveToEightIsAccepted() throws Exception {
        when(port.findCandidatesInBounds(any())).thenReturn(List.of());
        double[] cells = {0.003, 0.005, 0.01, 0.02};
        for (int level = 5; level <= 8; level++) {
            mvc.perform(clusters("37.49", "37.51", "127.02", "127.04", String.valueOf(level)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.level").value(level))
                    .andExpect(jsonPath("$.data.cellDegrees").value(cells[level - 5]))
                    .andExpect(jsonPath("$.data.items", hasSize(0)));
        }
    }

    @Test
    void levelAndBoundsValidationReuseTheExistingCodes() throws Exception {
        for (String level : List.of("4", "9", "0", "-5", "5.5", "abc", "")) {
            failure(mvc.perform(clusters("37.49", "37.51", "127.02", "127.04", level)), 400, "INVALID_PARAMETER");
        }
        // Field details of the @Min/@Max path are checked in the full context (StationMapIntegrationTest); this
        // standalone setup has no method-validation proxy, so the service's own range check answers here.
        failure(mvc.perform(clusters("37.49", "37.51", "127.02", "127.04", null)), 400, "INVALID_PARAMETER")
                .andExpect(jsonPath("$.error.details[0].field").value("level"));
        failure(mvc.perform(clusters(null, "37.51", "127.02", "127.04", "5")), 400, "INVALID_PARAMETER")
                .andExpect(jsonPath("$.error.details[0].field").value("south"));
        // Coordinates out of range or not finite: INVALID_COORDINATE (StationBounds/Location, as for the viewport).
        failure(mvc.perform(clusters("-91", "37.51", "127.02", "127.04", "5")), 400, "INVALID_COORDINATE");
        failure(mvc.perform(clusters("37.49", "37.51", "127.02", "181", "5")), 400, "INVALID_COORDINATE");
        failure(mvc.perform(clusters("NaN", "37.51", "127.02", "127.04", "5")), 400, "INVALID_COORDINATE");
        // Inverted, empty or wider than 1°: INVALID_PARAMETER.
        failure(mvc.perform(clusters("37.51", "37.49", "127.02", "127.04", "5")), 400, "INVALID_PARAMETER");
        failure(mvc.perform(clusters("37.49", "37.49", "127.02", "127.04", "5")), 400, "INVALID_PARAMETER");
        failure(mvc.perform(clusters("37.0", "38.01", "127.02", "127.04", "5")), 400, "INVALID_PARAMETER");
        failure(mvc.perform(clusters("37.49", "37.51", "126.5", "127.6", "5")), 400, "INVALID_PARAMETER");
        verifyNoInteractions(port);
        // Exactly 1° on both axes is still allowed (unchanged StationBounds rule).
        when(port.findCandidatesInBounds(any())).thenReturn(List.of());
        mvc.perform(clusters("37.0", "38.0", "126.5", "127.5", "8")).andExpect(status().isOk());
    }

    private static Station station(int i) {
        return new Station("S" + i, "S" + i, "서울", new Location(37.5, 127.0), 10, 1, i, NOW);
    }

    @Test
    void viewportAbove500ReturnsTheNearest500AsPartialWithResultTruncated() throws Exception {
        var bounds = new StationBounds(37.4, 37.6, 126.9, 127.1);
        when(port.findInBounds(bounds)).thenReturn(IntStream.range(0, 501).mapToObj(StationMapEndpointsTest::station).toList());

        mvc.perform(get("/api/v1/stations/viewport").param("south", "37.4").param("north", "37.6")
                        .param("west", "126.9").param("east", "127.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(500)))
                .andExpect(jsonPath("$.data[0].stationId").value("S0"))
                .andExpect(jsonPath("$.data[499].stationId").value("S499"))
                .andExpect(jsonPath("$.meta.partial").value(true))
                .andExpect(jsonPath("$.meta.warnings[*].code").value(contains("RESULT_TRUNCATED")));
        verify(port).findInBounds(bounds);
    }

    @Test
    void viewportAtExactly500IsUnchanged() throws Exception {
        when(port.findInBounds(any())).thenReturn(IntStream.range(0, 500).mapToObj(StationMapEndpointsTest::station).toList());

        mvc.perform(get("/api/v1/stations/viewport").param("south", "37.4").param("north", "37.6")
                        .param("west", "126.9").param("east", "127.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(500)))
                .andExpect(jsonPath("$.meta.partial").doesNotExist())
                .andExpect(jsonPath("$.meta.warnings").doesNotExist());
    }

    @Test
    void viewportValidationIsUnchanged() throws Exception {
        failure(mvc.perform(get("/api/v1/stations/viewport").param("south", "37.6").param("north", "37.5")
                .param("west", "127.0").param("east", "127.1")), 400, "INVALID_PARAMETER");
        failure(mvc.perform(get("/api/v1/stations/viewport").param("south", "-95").param("north", "37.5")
                .param("west", "127.0").param("east", "127.1")), 400, "INVALID_COORDINATE");
        verifyNoInteractions(port);
    }
}
