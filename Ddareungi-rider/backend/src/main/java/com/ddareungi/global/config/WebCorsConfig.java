package com.ddareungi.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for the public API, limited to the configured web origins (spec 25). */
@Configuration(proxyBeanMethods = false)
public class WebCorsConfig implements WebMvcConfigurer {
    static final String[] ALLOWED_METHODS = {"GET", "POST", "OPTIONS"};
    static final String[] ALLOWED_HEADERS = {
            "Content-Type", "X-Request-Id", "X-Client-Platform", "X-Client-Version", "Accept-Language"};
    static final String[] EXPOSED_HEADERS = {"X-Request-Id", "Retry-After", "Deprecation", "Sunset"};

    private final CorsProperties properties;

    public WebCorsConfig(CorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // No configured origin keeps the previous behaviour: no CORS headers at all.
        if (properties.allowedOrigins().isEmpty()) return;
        registry.addMapping("/api/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods(ALLOWED_METHODS)
                .allowedHeaders(ALLOWED_HEADERS)
                .exposedHeaders(EXPOSED_HEADERS)
                // The API uses no cookies; never let browsers attach credentials.
                .allowCredentials(false)
                .maxAge(3600);
    }
}
