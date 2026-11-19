package com.ddareungi.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebCorsConfigTest {
    private static final String WEB = "https://service.example.com";
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(MvcConfig.class);

    @EnableWebMvc
    @EnableConfigurationProperties(CorsProperties.class)
    @Import({WebCorsConfig.class, PingController.class})
    static class MvcConfig {}

    @RestController
    static class PingController {
        @GetMapping("/api/v1/ping") String ping() { return "ok"; }
        @GetMapping("/internal/ping") String internal() { return "ok"; }
    }

    private static MockMvc mvc(org.springframework.context.ApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
    }

    @Test void allowedOriginPreflightGetsWhitelistHeaders() {
        runner.withPropertyValues("app.cors.allowed-origins=" + WEB + ",http://localhost:5173").run(context -> {
            mvc(context.getSourceApplicationContext()).perform(options("/api/v1/ping")
                            .header(HttpHeaders.ORIGIN, WEB)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Request-Id, X-Client-Platform"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "X-Request-Id, X-Client-Platform"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        });
    }

    @Test void allowedOriginActualRequestExposesOperationalHeaders() {
        runner.withPropertyValues("app.cors.allowed-origins=" + WEB).run(context -> {
            var result = mvc(context.getSourceApplicationContext())
                    .perform(get("/api/v1/ping").header(HttpHeaders.ORIGIN, WEB))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB))
                    .andReturn();
            assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                    .contains("X-Request-Id", "Retry-After", "Deprecation", "Sunset");
        });
    }

    @Test void disallowedOriginMethodOrHeaderIsRejected() {
        runner.withPropertyValues("app.cors.allowed-origins=" + WEB).run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(options("/api/v1/ping")
                            .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            mvc.perform(get("/api/v1/ping").header(HttpHeaders.ORIGIN, "https://evil.example.com"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            mvc.perform(options("/api/v1/ping")
                            .header(HttpHeaders.ORIGIN, WEB)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                    .andExpect(status().isForbidden());
            mvc.perform(options("/api/v1/ping")
                            .header(HttpHeaders.ORIGIN, WEB)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                    .andExpect(status().isForbidden());
            // Only /api/** is mapped.
            mvc.perform(get("/internal/ping").header(HttpHeaders.ORIGIN, WEB))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        });
    }

    @Test void emptyWhitelistKeepsResponsesFreeOfCorsHeaders() {
        runner.run(context -> {
            assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty();
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(get("/api/v1/ping").header(HttpHeaders.ORIGIN, WEB))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
            mvc.perform(options("/api/v1/ping")
                            .header(HttpHeaders.ORIGIN, WEB)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
        });
    }

    @Test void wildcardOrInexactOriginsFailStartup() {
        for (String value : new String[] {"*", "https://*.example.com", "service.example.com",
                "https://service.example.com/", "https://service.example.com/app", "ftp://service.example.com"}) {
            runner.withPropertyValues("app.cors.allowed-origins=" + value)
                    .run(context -> assertThat(context).as(value).hasFailed());
        }
    }

    @Test void propertiesRejectWildcardDirectly() {
        assertThatThrownBy(() -> new CorsProperties(java.util.List.of(WEB, "*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CorsProperties(java.util.List.of(" " + WEB + " ", "")).allowedOrigins()).containsExactly(WEB);
    }
}
