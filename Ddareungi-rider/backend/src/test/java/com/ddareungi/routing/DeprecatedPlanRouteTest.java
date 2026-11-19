package com.ddareungi.routing;

import com.ddareungi.global.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Phase 3 decisions 1 and 23: the legacy {@code POST /api/v1/routes/plan} keeps working but is announced as
 * deprecated by the default {@code api.deprecations} entry in application.yml; {@code /routes/search} is not.
 * Routing is disabled in the local profile, so both answer 503 - the headers must still be present (or absent).
 */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "management.server.port=-1",
        "kakao.maps.rest-api-key="
})
class DeprecatedPlanRouteTest {
    private static final String BODY = """
            {"origin":{"latitude":37.4979,"longitude":127.0276},"destination":{"latitude":37.5,"longitude":127.03}}""";

    @Autowired WebApplicationContext context;
    @Autowired RequestIdFilter requestIdFilter;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter).build();
    }

    @Test void legacyPlanCarriesDeprecationAndSunsetHeaders() throws Exception {
        mvc().perform(post("/api/v1/routes/plan").with(request -> { request.setRemoteAddr("10.99.1.1"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(header().string("Deprecation", "@1794873600"))
                .andExpect(header().string("Sunset", "Wed, 30 Jun 2027 23:59:59 GMT"));
    }

    @Test void searchIsNotDeprecated() throws Exception {
        mvc().perform(post("/api/v1/routes/search").with(request -> { request.setRemoteAddr("10.99.1.2"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(header().doesNotExist("Deprecation"))
                .andExpect(header().doesNotExist("Sunset"));
    }
}
