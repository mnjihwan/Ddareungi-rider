package com.ddareungi.global.ratelimit;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The interceptor in the real local application context (memory limiter for every group). Small limits keep the
 * test fast; every test uses its own client address because the buckets live as long as the cached context.
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        "kakao.maps.rest-api-key=",
        "app.cors.allowed-origins=https://service.example.com",
        "ratelimit.stations.burst=2",
        "ratelimit.stations.per-minute=1",
        "ratelimit.places.burst=1",
        "ratelimit.places.per-minute=1",
        "ratelimit.routes.burst=1",
        "ratelimit.routes.per-minute=1"
})
class RateLimitIntegrationTest {
    static final String ORIGIN = "https://service.example.com";
    static final String ROUTE_BODY = "{\"origin\":{\"latitude\":37.4979,\"longitude\":127.0276},"
            + "\"destination\":{\"latitude\":37.5045,\"longitude\":127.0248}}";

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
    }

    private static MockHttpServletRequestBuilder nearby(String ip) {
        return get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276")
                .with(request -> { request.setRemoteAddr(ip); return request; });
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> { request.setRemoteAddr(ip); return request; });
    }

    @Test void exceedingTheLimitReturns429EnvelopeWithRetryAfterAndRequestId() throws Exception {
        String ip = "10.0.0.1";
        mvc.perform(nearby(ip)).andExpect(status().isOk());
        mvc.perform(nearby(ip)).andExpect(status().isOk());
        mvc.perform(nearby(ip).header(RequestIdFilter.HEADER, "rate-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(header().string(RequestIdFilter.HEADER, "rate-1"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.meta.requestId").value("rate-1"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test void crossOriginRejectionStillCarriesCorsAndExposesRetryAfter() throws Exception {
        String ip = "10.0.0.2";
        mvc.perform(nearby(ip).header(HttpHeaders.ORIGIN, ORIGIN)).andExpect(status().isOk());
        mvc.perform(nearby(ip).header(HttpHeaders.ORIGIN, ORIGIN)).andExpect(status().isOk());
        mvc.perform(nearby(ip).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Retry-After")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Request-Id")))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test void preflightIsNeverCounted() throws Exception {
        String ip = "10.0.0.3";
        mvc.perform(nearby(ip)).andExpect(status().isOk());
        mvc.perform(nearby(ip)).andExpect(status().isOk());
        for (int i = 0; i < 5; i++) {
            mvc.perform(from(options("/api/v1/stations/nearby"), ip)
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                    .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        }
    }

    @Test void groupsAndClientsHaveSeparateBuckets() throws Exception {
        String ip = "10.0.0.4";
        // places: one request, then 429 — no matter what the first one answered (no Kakao key here).
        mvc.perform(from(get("/api/v1/places/search").param("query", "강남역"), ip))
                .andExpect(status().is(not(429)));
        mvc.perform(from(get("/api/v1/places/search").param("query", "강남역"), ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
        // Another place endpoint shares the places bucket.
        mvc.perform(from(get("/api/v1/places/reverse").param("latitude", "37.4979").param("longitude", "127.0276"), ip))
                .andExpect(status().isTooManyRequests());
        // Routes and stations still have their own budget for the same client.
        mvc.perform(from(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(ROUTE_BODY), ip))
                .andExpect(status().is(not(429)));
        mvc.perform(from(post("/api/v1/routes/plan").contentType(MediaType.APPLICATION_JSON).content(ROUTE_BODY), ip))
                .andExpect(status().isTooManyRequests());
        mvc.perform(nearby(ip)).andExpect(status().isOk());
        // Another client is unaffected.
        mvc.perform(from(get("/api/v1/places/search").param("query", "강남역"), "10.0.0.5"))
                .andExpect(status().is(not(429)));
    }

    @Test void statusApisShareTheStationsGroup() throws Exception {
        String ip = "10.0.0.6";
        mvc.perform(from(get("/api/v1/status"), ip)).andExpect(status().isOk());
        mvc.perform(from(get("/api/v1/routes/status"), ip)).andExpect(status().isOk());
        mvc.perform(nearby(ip)).andExpect(status().isTooManyRequests());
    }

    @Test void unknownPathsAreNotLimited() throws Exception {
        String ip = "10.0.0.7";
        for (int i = 0; i < 4; i++) {
            mvc.perform(from(get("/api/v1/does-not-exist"), ip)).andExpect(status().isNotFound());
        }
    }
}
