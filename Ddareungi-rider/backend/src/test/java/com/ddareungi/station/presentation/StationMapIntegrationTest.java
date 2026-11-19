package com.ddareungi.station.presentation;

import com.ddareungi.global.ratelimit.RateLimitGroup;
import com.ddareungi.global.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R4 in the real local application context (sample data: ST-001..003): the clusters endpoint is wired, the
 * {@code station.map.*} properties bind (viewport cap overridden to 2 here), and {@code /stations/clusters} is in the
 * {@code stations} rate-limit group (small limits; each test uses its own client address).
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        "kakao.maps.rest-api-key=",
        "station.map.viewport-max-items=2",
        "ratelimit.stations.burst=3",
        "ratelimit.stations.per-minute=1"
})
class StationMapIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> { request.setRemoteAddr(ip); return request; });
    }

    private static MockHttpServletRequestBuilder clusters(String level) {
        return get("/api/v1/stations/clusters").param("south", "37.49").param("north", "37.51")
                .param("west", "127.02").param("east", "127.04").param("level", level);
    }

    @Test void clustersOnTheSampleData() throws Exception {
        // Level 8 (0.02°): ST-001 (latitude cell 1874) alone, ST-002 and ST-003 (cell 1875) together.
        mvc.perform(from(clusters("8"), "10.88.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.level").value(8))
                .andExpect(jsonPath("$.data.cellDegrees").value(0.02))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].latitude").value(37.4979))
                .andExpect(jsonPath("$.data.items[0].longitude").value(127.0276))
                .andExpect(jsonPath("$.data.items[0].stationCount").value(1))
                .andExpect(jsonPath("$.data.items[0].parkingBikeTotCnt").value(7))
                // Mean of the two stations, rounded to 1e-7° (raw double 127.03059999999999).
                .andExpect(jsonPath("$.data.items[1].latitude").value(37.50255))
                .andExpect(jsonPath("$.data.items[1].longitude").value(127.0306))
                .andExpect(jsonPath("$.data.items[1].stationCount").value(2))
                .andExpect(jsonPath("$.data.items[1].parkingBikeTotCnt").value(13))
                .andExpect(jsonPath("$.data.items[1].freshCount").value(2))
                .andExpect(jsonPath("$.meta.partial").doesNotExist());
        // Level 5 (0.003°): one cell per station.
        mvc.perform(from(clusters("5"), "10.88.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[*].stationCount").value(contains(1, 1, 1)));
    }

    @Test void levelValidationInTheRealContext() throws Exception {
        String ip = "10.88.0.4";
        mvc.perform(from(clusters("4"), ip))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.error.details[0].field").value("level"));
        mvc.perform(from(clusters("9"), ip))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.error.details[0].field").value("level"));
        mvc.perform(from(get("/api/v1/stations/clusters").param("south", "95").param("north", "37.51")
                        .param("west", "127.02").param("east", "127.04").param("level", "5"), ip))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_COORDINATE"));
    }

    @Test void viewportCapIsConfigurable() throws Exception {
        mvc.perform(from(get("/api/v1/stations/viewport").param("south", "37.49").param("north", "37.51")
                        .param("west", "127.02").param("east", "127.04"), "10.88.0.2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.meta.partial").value(true))
                .andExpect(jsonPath("$.meta.warnings[*].code").value(contains("RESULT_TRUNCATED")));
    }

    @Test void clustersShareTheStationsRateLimitGroup() throws Exception {
        assertThat(RateLimitGroup.classify("/api/v1/stations/clusters")).isEqualTo(RateLimitGroup.STATIONS);
        String ip = "10.88.0.3";
        // The bucket is shared with the other station APIs: two other station calls, then clusters, then 429.
        mvc.perform(from(get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276"), ip))
                .andExpect(status().isOk());
        mvc.perform(from(get("/api/v1/stations/viewport").param("south", "37.49").param("north", "37.51")
                .param("west", "127.02").param("east", "127.04"), ip)).andExpect(status().isOk());
        mvc.perform(from(clusters("6"), ip)).andExpect(status().isOk());
        mvc.perform(from(clusters("6"), ip).header(RequestIdFilter.HEADER, "cl-429"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.meta.requestId").value("cl-429"));
        // Invalid requests are counted too (the interceptor runs before validation): still 429 for this client.
        mvc.perform(from(clusters("4"), ip)).andExpect(status().isTooManyRequests());
    }
}
