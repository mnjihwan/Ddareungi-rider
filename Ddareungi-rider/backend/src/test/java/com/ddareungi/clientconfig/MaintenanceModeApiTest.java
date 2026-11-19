package com.ddareungi.clientconfig;

import com.ddareungi.global.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Maintenance on (decision 21) in the real local context. Actuator shares the API port here
 * ({@code management.server.port == server.port}) so its exclusion can be checked through MockMvc.
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.port=18080",
        "management.server.port=18080",
        "kakao.maps.rest-api-key=",
        "app.cors.allowed-origins=" + MaintenanceModeApiTest.ORIGIN,
        "ratelimit.stations.burst=1",
        "ratelimit.stations.per-minute=1",
        "ratelimit.routes.burst=1",
        "ratelimit.routes.per-minute=1",
        "client.web.maintenance=true",
        "client.web.maintenance-message=새벽 2시까지 점검합니다.",
        "api.deprecations[0].pattern=/api/v1/status",
        "api.deprecations[0].deprecation=2023-06-30T23:59:59Z",
        "api.deprecations[0].sunset=2027-06-30T23:59:59Z"
})
class MaintenanceModeApiTest {
    static final String ORIGIN = "https://service.example.com";
    static final String MESSAGE = "새벽 2시까지 점검합니다.";
    static final String ROUTE_BODY = "{\"origin\":{\"latitude\":37.4979,\"longitude\":127.0276},"
            + "\"destination\":{\"latitude\":37.5045,\"longitude\":127.0248}}";
    private static final AtomicInteger CLIENT = new AtomicInteger();

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    MockMvc mvc;
    String ip;

    @BeforeEach void setup() {
        ip = "10.89.0." + CLIENT.incrementAndGet();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
    }

    private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> { request.setRemoteAddr(ip); return request; });
    }

    @Test void apiV1Answers503EnvelopeWithRequestId() throws Exception {
        mvc.perform(from(get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276"))
                        .header(RequestIdFilter.HEADER, "mnt-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(RequestIdFilter.HEADER, "mnt-1"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_MODE"))
                .andExpect(jsonPath("$.error.message").value(MESSAGE))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.meta.requestId").value("mnt-1"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test void everyApiV1PathIsBlockedBeforeValidation() throws Exception {
        mvc.perform(from(get("/api/v1/stations/nearby"))) // missing parameters: 503, not 400
                .andExpect(status().isServiceUnavailable());
        mvc.perform(from(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(ROUTE_BODY)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_MODE"));
        mvc.perform(from(get("/api/v1/places/search").param("query", "강남역")))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(from(get("/api/v1/status/realtime"))).andExpect(status().isServiceUnavailable());
        mvc.perform(from(get("/api/v1/stations/ST-1"))).andExpect(status().isServiceUnavailable());
    }

    @Test void maintenanceRunsBeforeRateLimitSoNoTokensAreUsed() throws Exception {
        // Burst 1: without the ordering the second request would be 429.
        for (int i = 0; i < 5; i++) {
            mvc.perform(from(get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
            mvc.perform(from(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(ROUTE_BODY)))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Test void clientConfigStaysAvailableAndReportsMaintenance() throws Exception {
        for (String query : new String[]{"platform=web", "platform=android&appVersion=0.1.0",
                "platform=ios&appVersion=0.1.0"}) {
            mvc.perform(from(get("/api/client-config?" + query)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.data.maintenance").value(true))
                    .andExpect(jsonPath("$.data.maintenanceMessage").value(MESSAGE));
        }
        // Its own validation still applies.
        mvc.perform(from(get("/api/client-config?platform=android")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    }

    @Test void actuatorIsNotBlocked() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test void crossOriginCallersCanReadTheError() throws Exception {
        mvc.perform(from(get("/api/v1/status")).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(jsonPath("$.error.code").value("MAINTENANCE_MODE"));
        mvc.perform(from(options("/api/v1/stations/nearby")).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
    }

    @Test void maintenanceErrorKeepsDeprecationHeaders() throws Exception {
        mvc.perform(from(get("/api/v1/status")).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Deprecation", "@1688169599"))
                .andExpect(header().string("Sunset", "Wed, 30 Jun 2027 23:59:59 GMT"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Deprecation")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Sunset")));
    }
}
