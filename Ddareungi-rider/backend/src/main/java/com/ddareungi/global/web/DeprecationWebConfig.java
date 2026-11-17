package com.ddareungi.global.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * One {@link DeprecationInterceptor} per {@code api.deprecations[]} entry, ordered before maintenance and rate limit
 * ({@link DeprecationInterceptor#ORDER}). Nothing is registered by default.
 */
@Configuration(proxyBeanMethods = false)
public class DeprecationWebConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(DeprecationWebConfig.class);

    private final DeprecationProperties properties;

    public DeprecationWebConfig(DeprecationProperties properties) {
        this.properties = properties;
        for (DeprecationProperties.Entry entry : properties.deprecations()) {
            log.info("Deprecated API {}: Deprecation={}, Sunset={}", entry.pattern(), entry.deprecationHeader(),
                    entry.sunsetHeader());
        }
    }

    /** Also called for the management child context (it inherits the configurers); the paths never match there. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        for (DeprecationProperties.Entry entry : properties.deprecations()) {
            registry.addInterceptor(new DeprecationInterceptor(entry))
                    .addPathPatterns(entry.pattern())
                    .order(DeprecationInterceptor.ORDER);
        }
    }
}
