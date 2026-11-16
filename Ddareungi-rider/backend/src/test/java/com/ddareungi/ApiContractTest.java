package com.ddareungi;

import com.ddareungi.global.web.RequestIdFilter;
import com.ddareungi.place.PlaceResponse;
import com.ddareungi.routing.presentation.RoutePlanResponse;
import com.ddareungi.station.presentation.StationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public API contract (spec 8/10/11.0, X1) against the real local application context. */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        // Make the "no Kakao key" path deterministic regardless of the developer's environment.
        "kakao.maps.rest-api-key="
})
class ApiContractTest {
    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    MockMvc mvc;

    /**
     * O3 rate limit (stations group: burst 30, keyed by remoteAddr) stays enabled here. The cached context is shared
     * by every test method, which together send more than 30 station/status requests, so each method gets its own
     * client address and stays far below the burst (largest method: about 15 requests).
     */
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach void setup() {
        String remoteAddr = "10.77.0." + CLIENT.incrementAndGet();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter)
                .defaultRequest(get("/").with(request -> { request.setRemoteAddr(remoteAddr); return request; }))
                .build();
    }

    private static ResultActions failure(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test void nearbyReturnsEnvelopeWithSourceFieldNames() throws Exception {
        mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276")
                        .header(RequestIdFilter.HEADER, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, "contract-1"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.meta.requestId").value("contract-1"))
                .andExpect(jsonPath("$.timestamp").isString())
                // Changed by A5: data is {center, radiusMeters, items} instead of a bare array (spec 11.1).
                .andExpect(jsonPath("$.data.center.latitude").value(37.4979))
                .andExpect(jsonPath("$.data.center.longitude").value(127.0276))
                .andExpect(jsonPath("$.data.radiusMeters").value(1000))
                .andExpect(jsonPath("$.data.items[0].stationId").value("ST-001"))
                .andExpect(jsonPath("$.data.items[0].stationName").isString())
                .andExpect(jsonPath("$.data.items[0].stationLatitude").value(37.4979))
                .andExpect(jsonPath("$.data.items[0].stationLongitude").value(127.0276))
                .andExpect(jsonPath("$.data.items[0].rackTotCnt").value(20))
                .andExpect(jsonPath("$.data.items[0].parkingBikeTotCnt").value(7))
                // Changed by P2-1 (A3): shared is now provided (sample 7/20 → 35.0, was always null), and
                // realtimeStatus/realtimeAgeSeconds are new; an all-FRESH list carries no partial/warnings meta.
                .andExpect(jsonPath("$.data.items[0].shared").value(35.0))
                .andExpect(jsonPath("$.data.items[0].realtimeStatus").value("FRESH"))
                .andExpect(jsonPath("$.data.items[0].realtimeAgeSeconds").isNumber())
                .andExpect(jsonPath("$.meta.partial").doesNotExist())
                .andExpect(jsonPath("$.meta.warnings").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].realtimeUpdatedAt").isString())
                .andExpect(jsonPath("$.data.items[0].distanceMeters").isNumber())
                .andExpect(jsonPath("$.data.items[0].address").isString())
                .andExpect(jsonPath("$.data.items[0].location").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].rackTotalCount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].availableBikeCount").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].updatedAt").doesNotExist());
    }

    /** A5: sort/limit/onlyAvailable on the three sample stations (ST-001 7 bikes, ST-002 2, ST-003 11). */
    @Test void nearbySortLimitAndOnlyAvailable() throws Exception {
        mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276")
                        .param("radius", "2000").param("sort", "parkingBikeTotCnt").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.radiusMeters").value(2000))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].stationId").value("ST-003"))
                .andExpect(jsonPath("$.data.items[1].stationId").value("ST-001"))
                .andExpect(jsonPath("$.meta.partial").doesNotExist());
        mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276")
                        .param("radius", "2000").param("sort", "name").param("onlyAvailable", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].stationName").value("강남역 2번 출구"))
                .andExpect(jsonPath("$.data.items[1].stationName").value("신논현역 6번 출구"))
                .andExpect(jsonPath("$.data.items[2].stationName").value("역삼역 4번 출구"));
    }

    @Test void stationDtoHasNoLegacyFieldNames() {
        var names = Arrays.stream(StationResponse.class.getRecordComponents()).map(RecordComponent::getName).toList();
        // Changed by P2-1 (A3): realtimeStatus and realtimeAgeSeconds added (spec 11.0 order around realtimeUpdatedAt).
        assertThat(names).containsExactly("stationId", "stationName", "stationLatitude", "stationLongitude",
                "rackTotCnt", "parkingBikeTotCnt", "shared", "realtimeStatus", "realtimeUpdatedAt", "realtimeAgeSeconds",
                "distanceMeters", "address");
        assertThat(names).doesNotContain("location", "rackTotalCount", "availableBikeCount", "updatedAt");
        var plan = Arrays.stream(RoutePlanResponse.class.getRecordComponents()).map(RecordComponent::getType).toList();
        assertThat(plan).contains(StationResponse.class);
        var place = Arrays.stream(PlaceResponse.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(place).containsExactly("name", "address", "location", "sourceUrl", "categoryGroupCode");
    }

    @Test void batchViewportAndSearchUseTheSameStationShape() throws Exception {
        mvc.perform(post("/api/v1/stations/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stationIds\":[\"ST-002\"],\"latitude\":37.4979,\"longitude\":127.0276}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stationId").value("ST-002"))
                .andExpect(jsonPath("$.data[0].parkingBikeTotCnt").value(2))
                .andExpect(jsonPath("$.data[0].availableBikeCount").doesNotExist());
        mvc.perform(get("/api/v1/stations/viewport").param("south", "37.49").param("north", "37.51")
                        .param("west", "127.02").param("east", "127.04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stationLatitude").isNumber());
        mvc.perform(get("/api/v1/stations/search").param("query", "강남").param("latitude", "37.4979")
                        .param("longitude", "127.0276"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stationName").value(containsString("강남")));
    }

    /** A4: detail, realtime batch and realtime status on the local sample data. */
    @Test void detailRealtimeBatchAndRealtimeStatus() throws Exception {
        mvc.perform(get("/api/v1/stations/ST-002")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stationId").value("ST-002"))
                .andExpect(jsonPath("$.data.realtimeStatus").value("FRESH"))
                .andExpect(jsonPath("$.data.distanceMeters").doesNotExist());
        failure(mvc.perform(get("/api/v1/stations/ST-999")), 404, "STATION_NOT_FOUND");
        failure(mvc.perform(get("/api/v1/stations/bad_id")), 400, "INVALID_PARAMETER");
        failure(mvc.perform(get("/api/v1/stations/batch")), 405, "METHOD_NOT_ALLOWED");
        mvc.perform(get("/api/v1/stations/realtime").param("stationIds", "ST-003,ST-999,ST-001,ST-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].stationId").value("ST-003"))
                .andExpect(jsonPath("$.data.items[1].stationId").value("ST-001"))
                .andExpect(jsonPath("$.data.notFoundStationIds[0]").value("ST-999"))
                .andExpect(jsonPath("$.data.excludedStationIds").isArray())
                .andExpect(jsonPath("$.data.unavailableStationIds").isArray())
                .andExpect(jsonPath("$.meta.partial").value(true));
        failure(mvc.perform(get("/api/v1/stations/realtime").param("stationIds", "ST-001,,ST-002")), 400, "INVALID_PARAMETER");
        mvc.perform(get("/api/v1/status/realtime")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NORMAL"))
                .andExpect(jsonPath("$.data.totalStationCount").value(3))
                .andExpect(jsonPath("$.data.freshStationCount").value(3))
                .andExpect(jsonPath("$.data.ageSeconds").isNumber());
    }

    @Test void statusEndpointsUseDtos() throws Exception {
        mvc.perform(get("/api/v1/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.mode").value("sample"));
        mvc.perform(get("/api/v1/routes/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false));
    }

    @Test void invalidCoordinatesAndRadius() throws Exception {
        failure(mvc.perform(get("/api/v1/stations/nearby").param("latitude", "91").param("longitude", "127")),
                400, "INVALID_COORDINATE")
                .andExpect(jsonPath("$.error.details[0].field").value("latitude"));
        // Changed by A5 (D11): the parameter is `radius`; `radiusMeters` has no alias and is ignored.
        failure(mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.5").param("longitude", "127")
                .param("radius", "10")), 400, "INVALID_RADIUS")
                .andExpect(jsonPath("$.error.details[0].field").value("radius"));
        failure(mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.5").param("longitude", "127")
                .param("radius", "5001")), 400, "INVALID_RADIUS");
        failure(mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.5").param("longitude", "127")
                .param("limit", "0")), 400, "INVALID_LIMIT")
                .andExpect(jsonPath("$.error.details[0].field").value("limit"));
        failure(mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.5").param("longitude", "127")
                .param("limit", "101")), 400, "INVALID_LIMIT");
        failure(mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.5").param("longitude", "127")
                .param("sort", "bikes")), 400, "INVALID_PARAMETER")
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));
        mvc.perform(get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276")
                        .param("radiusMeters", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.radiusMeters").value(1000));
        failure(mvc.perform(get("/api/v1/stations/nearby").param("latitude", "NaN").param("longitude", "127")),
                400, "INVALID_COORDINATE");
        failure(mvc.perform(post("/api/v1/stations/batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stationIds\":[],\"latitude\":95,\"longitude\":127}")), 400, "INVALID_COORDINATE");
        failure(mvc.perform(get("/api/v1/stations/viewport").param("south", "37.6").param("north", "37.5")
                .param("west", "127.0").param("east", "127.1")), 400, "INVALID_PARAMETER");
    }

    @Test void missingAndMalformedParameters() throws Exception {
        failure(mvc.perform(get("/api/v1/stations/nearby").param("longitude", "127")), 400, "INVALID_PARAMETER")
                .andExpect(jsonPath("$.error.details[0].field").value("latitude"));
        failure(mvc.perform(get("/api/v1/stations/nearby").param("latitude", "abc").param("longitude", "127")),
                400, "INVALID_PARAMETER");
        failure(mvc.perform(get("/api/v1/stations/search").param("query", "x").param("latitude", "37.5")
                .param("longitude", "127")), 400, "INVALID_PARAMETER");
    }

    @Test void routePlanRequestValidationAndErrors() throws Exception {
        failure(mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content("{not json")),
                400, "INVALID_PARAMETER");
        failure(mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":{\"latitude\":91,\"longitude\":127},\"destination\":{\"latitude\":37.5,\"longitude\":127}}")),
                400, "INVALID_COORDINATE")
                .andExpect(jsonPath("$.error.details[0].field").value("latitude"));
        failure(mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"destination\":{\"latitude\":37.5,\"longitude\":127}}")), 400, "INVALID_PARAMETER")
                .andExpect(jsonPath("$.error.details[0].field").value("origin"));
        // Review L4: a missing coordinate (@NotNull) is INVALID_PARAMETER, not INVALID_COORDINATE.
        failure(mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":{\"longitude\":127},\"destination\":{\"latitude\":37.5,\"longitude\":127}}")),
                400, "INVALID_PARAMETER")
                .andExpect(jsonPath("$.error.details[0].field").value("latitude"));
        failure(mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.TEXT_PLAIN).content("hello")),
                415, "UNSUPPORTED_MEDIA_TYPE");
        // Routing is disabled in the local profile: ROUTING_NOT_CONFIGURED becomes 503 SERVICE_UNAVAILABLE.
        failure(mvc.perform(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":{\"latitude\":37.4979,\"longitude\":127.0276},\"destination\":{\"latitude\":37.5045,\"longitude\":127.0248}}")),
                503, "SERVICE_UNAVAILABLE");
    }

    @Test void placesWithoutKeyAndWithoutQuery() throws Exception {
        failure(mvc.perform(get("/api/v1/places/search").param("query", "강남역")), 503, "SERVICE_UNAVAILABLE");
        failure(mvc.perform(get("/api/v1/places/search")), 400, "PLACE_QUERY_REQUIRED")
                .andExpect(jsonPath("$.error.details[0].field").value("query"));
        failure(mvc.perform(get("/api/v1/places/search").param("query", "  ")), 400, "PLACE_QUERY_REQUIRED");
        failure(mvc.perform(get("/api/v1/places/reverse").param("latitude", "37.5").param("longitude", "127")),
                503, "SERVICE_UNAVAILABLE");
        failure(mvc.perform(get("/api/v1/places/nearby-subways").param("latitude", "99").param("longitude", "127")),
                400, "INVALID_COORDINATE");
    }

    @Test void unknownPathAndMethod() throws Exception {
        failure(mvc.perform(get("/api/v1/does-not-exist")), 404, "RESOURCE_NOT_FOUND");
        failure(mvc.perform(delete("/api/v1/status")), 405, "METHOD_NOT_ALLOWED");
    }

    @Test void requestIdIsReusedOrReplacedAndMatchesBody() throws Exception {
        var invalid = mvc.perform(get("/api/v1/stations/nearby").param("latitude", "91").param("longitude", "127")
                        .header(RequestIdFilter.HEADER, "not valid!"))
                .andExpect(header().string(RequestIdFilter.HEADER, not("not valid!")))
                .andReturn().getResponse();
        String generated = invalid.getHeader(RequestIdFilter.HEADER);
        assertThat(invalid.getContentAsString()).contains("\"requestId\":\"" + generated + "\"");
        failure(mvc.perform(get("/api/v1/does-not-exist").header(RequestIdFilter.HEADER, "keep-me")), 404, "RESOURCE_NOT_FOUND")
                .andExpect(header().string(RequestIdFilter.HEADER, "keep-me"))
                .andExpect(jsonPath("$.meta.requestId").value("keep-me"));
    }
}
