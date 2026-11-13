package com.ddareungi.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whitelist is bound and applied by the real application context, not only by a hand-made one. */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "app.cors.allowed-origins=https://service.example.com",
        // A separate management port cannot run without an embedded server; keep this test independent of it.
        "management.server.port=-1"
})
class CorsApplicationContextTest {
    @Autowired WebApplicationContext context;

    @Test void realApiAnswersWhitelistedPreflightOnly() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(options("/api/v1/status")
                        .header(HttpHeaders.ORIGIN, "https://service.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Accept-Language"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://service.example.com"));
        mvc.perform(get("/api/v1/status").header(HttpHeaders.ORIGIN, "https://other.example.com"))
                .andExpect(status().isForbidden());
    }
}
