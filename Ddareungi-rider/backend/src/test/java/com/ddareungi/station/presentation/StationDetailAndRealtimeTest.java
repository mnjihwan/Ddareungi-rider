package com.ddareungi.station.presentation;

import com.ddareungi.global.exception.GlobalExceptionHandler;
import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.application.FindNearbyStationsUseCase;
import com.ddareungi.station.application.StationQueryService;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A4: GET /stations/{stationId} and GET /stations/realtime (spec 11.2, 11.3) against a mocked port. */
class StationDetailAndRealtimeTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private StationSearchPort port;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        port = mock(StationSearchPort.class);
        var mapper = new StationViewMapper(RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));
        var clock = new DefaultListableBeanFactory();
        clock.registerSingleton("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new StationController(new FindNearbyStationsUseCase(port), mapper,
                        new StationQueryService(port)))
                .setControllerAdvice(new GlobalExceptionHandler(clock.getBeanProvider(Clock.class)))
                .addFilters(new RequestIdFilter()).build();
    }

    private static Station station(String id, Integer bikes, Instant updatedAt) {
        return new Station(id, "이름 " + id, "서울", new Location(37.5, 127.0), 20, bikes, 0, updatedAt, 35.0);
    }

    private static StationLookup found(String id, int bikes, RealtimeFreshness status, long age) {
        return StationLookup.of(new StationCandidate(station(id, bikes, NOW.minusSeconds(age)), status, age));
    }

    private static StationLookup unavailable(String id) {
        return StationLookup.of(new StationCandidate(station(id, null, null), RealtimeFreshness.UNAVAILABLE, null));
    }

    private static ResultActions failure(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---- detail ----

    @Test
    void detailReturnsStationWithoutDistance() throws Exception {
        when(port.lookup(List.of("ST-1"))).thenReturn(List.of(found("ST-1", 0, RealtimeFreshness.FRESH, 60)));

        mvc.perform(get("/api/v1/stations/ST-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.stationId").value("ST-1"))
                .andExpect(jsonPath("$.data.stationLatitude").value(37.5))
                .andExpect(jsonPath("$.data.rackTotCnt").value(20))
                // FRESH with 0 bikes is a normal 200 (spec 11.2).
                .andExpect(jsonPath("$.data.parkingBikeTotCnt").value(0))
                .andExpect(jsonPath("$.data.shared").value(35.0))
                .andExpect(jsonPath("$.data.realtimeStatus").value("FRESH"))
                .andExpect(jsonPath("$.data.realtimeAgeSeconds").value(60))
                .andExpect(jsonPath("$.data.realtimeUpdatedAt").value("2026-11-16T02:59:00Z"))
                .andExpect(jsonPath("$.data.distanceMeters").doesNotExist())
                .andExpect(jsonPath("$.meta.partial").doesNotExist());
    }

    @Test
    void staleDetailIsMarkedPartial() throws Exception {
        when(port.lookup(List.of("ST-1"))).thenReturn(List.of(found("ST-1", 4, RealtimeFreshness.STALE, 300)));

        mvc.perform(get("/api/v1/stations/ST-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realtimeStatus").value("STALE"))
                .andExpect(jsonPath("$.data.parkingBikeTotCnt").value(4))
                .andExpect(jsonPath("$.meta.partial").value(true))
                .andExpect(jsonPath("$.meta.warnings[*].code").value(contains("STALE_DATA")));
    }

    @Test
    void notFoundAndExcludedAre404AndUnavailableIs503() throws Exception {
        when(port.lookup(List.of("nope"))).thenReturn(List.of(StationLookup.notFound("nope")));
        when(port.lookup(List.of("closed"))).thenReturn(List.of(StationLookup.excluded("closed")));
        when(port.lookup(List.of("down"))).thenReturn(List.of(unavailable("down")));

        failure(mvc.perform(get("/api/v1/stations/nope")), 404, "STATION_NOT_FOUND");
        failure(mvc.perform(get("/api/v1/stations/closed")), 404, "STATION_NOT_FOUND");
        failure(mvc.perform(get("/api/v1/stations/down")), 503, "REALTIME_DATA_UNAVAILABLE");
    }

    @Test
    void malformedStationIdIs400WithoutTouchingThePort() throws Exception {
        failure(mvc.perform(get("/api/v1/stations/ST_1")), 400, "INVALID_PARAMETER")
                .andExpect(jsonPath("$.error.details[0].field").value("stationId"));
        failure(mvc.perform(get("/api/v1/stations/" + "A".repeat(31))), 400, "INVALID_PARAMETER");
        failure(mvc.perform(get("/api/v1/stations/{id}", "강남")), 400, "INVALID_PARAMETER");
        verifyNoInteractions(port);
    }

    @Test
    void catalogFailureIs503ServiceUnavailable() throws Exception {
        when(port.lookup(anyList())).thenThrow(new DataAccessResourceFailureException("db down"));
        failure(mvc.perform(get("/api/v1/stations/ST-1")), 503, "SERVICE_UNAVAILABLE");
        failure(mvc.perform(get("/api/v1/stations/realtime").param("stationIds", "ST-1")), 503, "SERVICE_UNAVAILABLE");
    }

    @Test
    void getBatchIsNotTreatedAsAStationId() throws Exception {
        // POST /batch is the only mapping for "batch": GET is 405 with Allow, never a lookup of station "batch".
        failure(mvc.perform(get("/api/v1/stations/batch")), 405, "METHOD_NOT_ALLOWED")
                .andExpect(header().string("Allow", containsString("POST")));
        verifyNoInteractions(port);
    }

    // ---- realtime batch ----

    @Test
    void batchSplitsIntoFourArraysInRequestOrderWithDuplicatesCollapsed() throws Exception {
        List<String> distinct = List.of("C", "A", "X", "E", "D", "B");
        when(port.lookup(distinct)).thenReturn(List.of(
                found("C", 3, RealtimeFreshness.FRESH, 10),
                StationLookup.notFound("A"),
                StationLookup.excluded("X"),
                unavailable("E"),
                found("D", 1, RealtimeFreshness.STALE, 200),
                StationLookup.notFound("B")));

        mvc.perform(get("/api/v1/stations/realtime").param("stationIds", "C,A,X,C,E,D,A,B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].stationId").value(contains("C", "D")))
                .andExpect(jsonPath("$.data.items[1].realtimeStatus").value("STALE"))
                .andExpect(jsonPath("$.data.items[0].distanceMeters").doesNotExist())
                .andExpect(jsonPath("$.data.notFoundStationIds").value(contains("A", "B")))
                .andExpect(jsonPath("$.data.excludedStationIds").value(contains("X")))
                .andExpect(jsonPath("$.data.unavailableStationIds").value(contains("E")))
                .andExpect(jsonPath("$.meta.partial").value(true))
                .andExpect(jsonPath("$.meta.warnings[*].code")
                        .value(contains("STALE_DATA", "REALTIME_DATA_UNAVAILABLE", "STATION_EXCLUDED")));
        verify(port).lookup(distinct);
    }

    @Test
    void allFreshBatchAlwaysCarriesTheThreeEmptyArraysAndNoPartial() throws Exception {
        when(port.lookup(List.of("A"))).thenReturn(List.of(found("A", 2, RealtimeFreshness.FRESH, 5)));

        mvc.perform(get("/api/v1/stations/realtime").param("stationIds", "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.notFoundStationIds", empty()))
                .andExpect(jsonPath("$.data.excludedStationIds", empty()))
                .andExpect(jsonPath("$.data.unavailableStationIds", empty()))
                .andExpect(jsonPath("$.meta.partial").doesNotExist());
    }

    @Test
    void allNotFoundOrExcludedIs200WithEmptyItems() throws Exception {
        when(port.lookup(List.of("A", "B"))).thenReturn(List.of(StationLookup.notFound("A"), StationLookup.excluded("B")));

        mvc.perform(get("/api/v1/stations/realtime").param("stationIds", "A,B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", empty()))
                .andExpect(jsonPath("$.data.notFoundStationIds").value(contains("A")))
                .andExpect(jsonPath("$.data.excludedStationIds").value(contains("B")))
                .andExpect(jsonPath("$.meta.partial").value(true));
    }

    @Test
    void publicIdsWithoutAnyServableItemIs503() throws Exception {
        when(port.lookup(List.of("A", "B"))).thenReturn(List.of(StationLookup.notFound("A"), unavailable("B")));
        failure(mvc.perform(get("/api/v1/stations/realtime").param("stationIds", "A,B")), 503, "REALTIME_DATA_UNAVAILABLE");
    }

    @Test
    void invalidStationIdsAre400() throws Exception {
        for (String raw : new String[]{"", ",", "A,", "A,,B", " A", "A, B", "A;B", "ST_1", "A".repeat(31)}) {
            failure(mvc.perform(get("/api/v1/stations/realtime").param("stationIds", raw)), 400, "INVALID_PARAMETER")
                    .andExpect(jsonPath("$.error.details[0].field").value("stationIds"))
                    .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(containsString("ST_1"))));
        }
        failure(mvc.perform(get("/api/v1/stations/realtime")), 400, "INVALID_PARAMETER");
        // 101 entries are rejected even when they are all duplicates (limit is counted before de-duplication).
        failure(mvc.perform(get("/api/v1/stations/realtime")
                .param("stationIds", String.join(",", Collections.nCopies(101, "A")))), 400, "INVALID_PARAMETER");
        verifyNoInteractions(port);
    }

    @Test
    void hundredIdsAreAccepted() throws Exception {
        List<String> ids = IntStream.range(0, 100).mapToObj(i -> "ST-" + i).toList();
        when(port.lookup(ids)).thenReturn(ids.stream().map(StationLookup::notFound).toList());
        mvc.perform(get("/api/v1/stations/realtime").param("stationIds", String.join(",", ids)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notFoundStationIds", hasSize(100)))
                .andExpect(jsonPath("$.data.items", empty()));
    }
}
