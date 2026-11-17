package com.ddareungi.clientconfig;

import com.ddareungi.global.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/client-config} and the deprecation headers in the real local context, maintenance off. Small
 * station limits make the 429 case cheap; every test uses its own client address.
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        "kakao.maps.rest-api-key=",
        "app.cors.allowed-origins=" + ClientConfigApiTest.ORIGIN,
        "ratelimit.stations.burst=2",
        "ratelimit.stations.per-minute=1",
        "client.platforms.android.minimum-version=1.9.0",
        "client.platforms.android.latest-version=1.10.0",
        "client.platforms.android.store-url=https://play.google.com/store/apps/details?id=com.ddareungi.rider",
        "client.platforms.ios.minimum-version=1.0.0",
        "client.platforms.ios.latest-version=1.0.0",
        "api.deprecations[0].pattern=/api/v1/stations/nearby",
        "api.deprecations[0].deprecation=2023-06-30T23:59:59Z",
        "api.deprecations[0].sunset=2027-06-30T23:59:59Z",
        "api.deprecations[0].link=https://docs.example.com/migration",
        "api.deprecations[1].pattern=/api/v1/status",
        "api.deprecations[1].deprecation=2026-12-01T00:00:00Z",
        "api.deprecations[1].sunset=2027-07-01T00:00:00Z"
})
class ClientConfigApiTest {
    static final String ORIGIN = "https://service.example.com";
    private static final AtomicInteger CLIENT = new AtomicInteger();

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;
    MockMvc mvc;
    String ip;

    @BeforeEach void setup() {
        ip = "10.88.0." + CLIENT.incrementAndGet();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
    }

    private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> { request.setRemoteAddr(ip); return request; });
    }

    private ResultActions config(String query) throws Exception {
        return mvc.perform(from(get("/api/client-config?" + query)).header(RequestIdFilter.HEADER, "cfg-1"));
    }

    @Test void androidResponseFollowsSpecShape() throws Exception {
        config("platform=android&appVersion=1.9.5&buildNumber=140")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(RequestIdFilter.HEADER, "cfg-1"))
                .andExpect(header().doesNotExist("Deprecation"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.meta.requestId").value("cfg-1"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.data.platform").value("android"))
                .andExpect(jsonPath("$.data.minimumVersion").value("1.9.0"))
                .andExpect(jsonPath("$.data.latestVersion").value("1.10.0"))
                .andExpect(jsonPath("$.data.forceUpdate").value(false))
                .andExpect(jsonPath("$.data.updateAvailable").value(true))
                .andExpect(jsonPath("$.data.maintenance").value(false))
                .andExpect(content().string(containsString("\"maintenanceMessage\":null")))
                .andExpect(jsonPath("$.data.supportedApiVersions[0]").value("v1"))
                .andExpect(jsonPath("$.data.supportedApiVersions.length()").value(1))
                .andExpect(jsonPath("$.data.storeUrl")
                        .value("https://play.google.com/store/apps/details?id=com.ddareungi.rider"));
    }

    @Test void numericVersionComparisonOverHttp() throws Exception {
        config("platform=android&appVersion=1.8.10").andExpect(jsonPath("$.data.forceUpdate").value(true))
                .andExpect(jsonPath("$.data.updateAvailable").value(true));
        config("platform=android&appVersion=1.10.0").andExpect(jsonPath("$.data.forceUpdate").value(false))
                .andExpect(jsonPath("$.data.updateAvailable").value(false));
        config("platform=ios&appVersion=1.0.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platform").value("ios"))
                .andExpect(jsonPath("$.data.forceUpdate").value(false))
                .andExpect(content().string(containsString("\"storeUrl\":null")));
    }

    @Test void webReturnsNullsAndFalse() throws Exception {
        config("platform=web")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.platform").value("web"))
                .andExpect(jsonPath("$.data.forceUpdate").value(false))
                .andExpect(jsonPath("$.data.updateAvailable").value(false))
                .andExpect(jsonPath("$.data.maintenance").value(false))
                .andExpect(content().string(containsString("\"minimumVersion\":null")))
                .andExpect(content().string(containsString("\"latestVersion\":null")))
                .andExpect(content().string(containsString("\"maintenanceMessage\":null")))
                .andExpect(content().string(containsString("\"storeUrl\":null")))
                .andExpect(jsonPath("$.data.supportedApiVersions[0]").value("v1"));
        // appVersion is not used for web, even when malformed.
        config("platform=web&appVersion=nope").andExpect(status().isOk());
    }

    @Test void invalidRequestsAre400InvalidParameter() throws Exception {
        String[][] cases = {
                {"platform=android", "appVersion"},
                {"platform=ios&appVersion=", "appVersion"},
                {"platform=android&appVersion=1.4", "appVersion"},
                {"platform=ios&appVersion=1.4.0-beta", "appVersion"},
                {"appVersion=1.4.0", "platform"},
                {"platform=windows&appVersion=1.4.0", "platform"},
                {"platform=Android&appVersion=1.4.0", "platform"},
        };
        for (String[] invalid : cases) {
            config(invalid[0])
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().string(RequestIdFilter.HEADER, "cfg-1"))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.error.details[0].field").value(invalid[1]))
                    .andExpect(jsonPath("$.meta.requestId").value("cfg-1"));
        }
    }

    @Test void clientConfigIsNotRateLimited() throws Exception {
        // Far above the station burst of 2 from one address.
        for (int i = 0; i < 20; i++) {
            config("platform=web").andExpect(status().isOk()).andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
        }
    }

    @Test void clientConfigAllowsCrossOrigin() throws Exception {
        mvc.perform(from(get("/api/client-config").param("platform", "web")).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
    }

    @Test void apiStaysAvailableWithoutMaintenance() throws Exception {
        mvc.perform(from(get("/api/v1/status"))).andExpect(status().isOk());
    }

    // --- Deprecation / Sunset ---------------------------------------------------------------------------------

    @Test void deprecatedPathCarriesHeadersAndExposesThemToBrowsers() throws Exception {
        mvc.perform(from(get("/api/v1/status")).header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "@1796083200"))
                .andExpect(header().string("Sunset", "Thu, 01 Jul 2027 00:00:00 GMT"))
                .andExpect(header().doesNotExist(HttpHeaders.LINK))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Deprecation")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Sunset")));
    }

    @Test void preflightExposesNothingButIsNotBroken() throws Exception {
        mvc.perform(from(options("/api/v1/status")).header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
    }

    @Test void validationErrorKeepsDeprecationHeaders() throws Exception {
        mvc.perform(from(get("/api/v1/stations/nearby")).param("latitude", "37.4979"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"))
                .andExpect(header().string("Deprecation", "@1688169599"))
                .andExpect(header().string("Sunset", "Wed, 30 Jun 2027 23:59:59 GMT"))
                .andExpect(header().string(HttpHeaders.LINK,
                        "<https://docs.example.com/migration>; rel=\"deprecation\""));
    }

    @Test void rateLimitErrorKeepsDeprecationHeaders() throws Exception {
        var nearby = from(get("/api/v1/stations/nearby").param("latitude", "37.4979").param("longitude", "127.0276"));
        mvc.perform(nearby).andExpect(status().isOk()).andExpect(header().string("Deprecation", "@1688169599"));
        mvc.perform(nearby).andExpect(status().isOk());
        mvc.perform(nearby.header(HttpHeaders.ORIGIN, ORIGIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().string("Deprecation", "@1688169599"))
                .andExpect(header().string("Sunset", "Wed, 30 Jun 2027 23:59:59 GMT"))
                .andExpect(header().exists(HttpHeaders.LINK))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Deprecation")));
    }

    @Test void interceptorOrderIsDeprecationThenMaintenanceThenRateLimit() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/stations/nearby");
        var chain = handlerMapping.getHandler(request);
        org.assertj.core.api.Assertions.assertThat(chain).isNotNull();
        var types = chain.getInterceptorList().stream().map(Object::getClass).toList();
        int deprecation = types.indexOf(com.ddareungi.global.web.DeprecationInterceptor.class);
        int maintenance = types.indexOf(com.ddareungi.global.web.MaintenanceInterceptor.class);
        int rateLimit = types.indexOf(com.ddareungi.global.ratelimit.RateLimitInterceptor.class);
        org.assertj.core.api.Assertions.assertThat(deprecation).isNotNegative().isLessThan(maintenance);
        org.assertj.core.api.Assertions.assertThat(maintenance).isLessThan(rateLimit);
    }

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMapping;

    @Test void otherPathsHaveNoDeprecationHeaders() throws Exception {
        mvc.perform(from(get("/api/v1/stations/realtime").param("stationIds", "ST-1")))
                .andExpect(header().doesNotExist("Deprecation"))
                .andExpect(header().doesNotExist("Sunset"));
        mvc.perform(from(get("/api/v1/status/realtime")))
                .andExpect(header().doesNotExist("Deprecation"));
    }
}
