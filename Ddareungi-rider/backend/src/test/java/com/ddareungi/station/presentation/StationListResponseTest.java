package com.ddareungi.station.presentation;

import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.application.FindNearbyStationsUseCase;
import com.ddareungi.station.application.StationQueryService;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A3 list rules on the wire: UNAVAILABLE removed, STALE kept with counts, meta.partial/warnings, new fields. */
class StationListResponseTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private StationSearchPort port;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        port = mock(StationSearchPort.class);
        var mapper = new StationViewMapper(RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));
        mvc = MockMvcBuilders.standaloneSetup(new StationController(new FindNearbyStationsUseCase(port), mapper,
                        new StationQueryService(port)))
                .addFilters(new RequestIdFilter()).build();
    }

    private static Station station(String id, Integer bikes, Instant updatedAt, Double shared) {
        return new Station(id, id, "서울", new Location(37.5, 127.0), null, bikes, 10, updatedAt, shared);
    }

    // Changed by A5: nearby now reads findWithin (candidates already classified by the port) and returns
    // {center, radiusMeters, items}; the A3 rule checked here (UNAVAILABLE removed, STALE kept, partial/warnings)
    // is unchanged. Items are sorted by distance, so distances differ to keep the original order.
    @Test
    void nearbyDropsUnavailableAndMarksPartial() throws Exception {
        when(port.findWithin(any(), anyInt())).thenReturn(List.of(
                candidate(station("fresh", 0, NOW.minusSeconds(5), 0.0, 10), RealtimeFreshness.FRESH, 5L),
                candidate(station("unknown", null, null, null, 20), RealtimeFreshness.UNAVAILABLE, null),
                candidate(station("stale", 6, NOW.minusSeconds(300), 140.0, 30), RealtimeFreshness.STALE, 300L)));

        mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.5").param("longitude", "127.0")
                        .header(RequestIdFilter.HEADER, "partial-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.requestId").value("partial-1"))
                .andExpect(jsonPath("$.meta.partial").value(true))
                .andExpect(jsonPath("$.meta.warnings[*].code").value(contains("STALE_DATA", "REALTIME_DATA_UNAVAILABLE")))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].stationId").value("fresh"))
                .andExpect(jsonPath("$.data.items[0].realtimeStatus").value("FRESH"))
                .andExpect(jsonPath("$.data.items[0].realtimeAgeSeconds").value(5))
                .andExpect(jsonPath("$.data.items[0].parkingBikeTotCnt").value(0))
                .andExpect(jsonPath("$.data.items[0].shared").value(0.0))
                .andExpect(jsonPath("$.data.items[0].rackTotCnt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[1].stationId").value("stale"))
                .andExpect(jsonPath("$.data.items[1].realtimeStatus").value("STALE"))
                .andExpect(jsonPath("$.data.items[1].realtimeAgeSeconds").value(300))
                .andExpect(jsonPath("$.data.items[1].parkingBikeTotCnt").value(6))
                .andExpect(jsonPath("$.data.items[1].shared").value(140.0));
    }

    private static Station station(String id, Integer bikes, Instant updatedAt, Double shared, double distance) {
        return new Station(id, id, "서울", new Location(37.5, 127.0), null, bikes, distance, updatedAt, shared);
    }

    private static StationCandidate candidate(Station station, RealtimeFreshness status, Long age) {
        return new StationCandidate(station, status, age);
    }

    @Test
    void allFreshListHasNoPartialMeta() throws Exception {
        when(port.findInBounds(any())).thenReturn(List.of(station("a", 3, NOW, 30.0)));

        mvc.perform(get("/api/v1/stations/viewport").param("south", "37.49").param("north", "37.51")
                        .param("west", "126.99").param("east", "127.01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.meta.partial").doesNotExist())
                .andExpect(jsonPath("$.meta.warnings").doesNotExist())
                .andExpect(jsonPath("$.data[0].realtimeStatus").value("FRESH"));
    }

    @Test
    void searchWithOnlyUnavailableReturnsEmptyPartialList() throws Exception {
        when(port.searchByName(any(), any())).thenReturn(List.of(station("old", 3, NOW.minusSeconds(601), null)));

        mvc.perform(get("/api/v1/stations/search").param("query", "강남").param("latitude", "37.5").param("longitude", "127.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)))
                .andExpect(jsonPath("$.meta.partial").value(true))
                .andExpect(jsonPath("$.meta.warnings[*].code").value(contains("REALTIME_DATA_UNAVAILABLE")));
    }
}
