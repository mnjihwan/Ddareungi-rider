package com.ddareungi.clientconfig;

import com.ddareungi.global.web.MaintenanceInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link MaintenanceInterceptor} for {@code /api/v1/**} (decision 21). Its order puts it before the rate
 * limit interceptor ({@link com.ddareungi.global.web.InterceptorOrder}), so blocked requests never consume rate limit
 * tokens.
 * {@code /api/client-config} lies outside the pattern and actuator runs on the management port; both are excluded
 * explicitly as well.
 */
@Configuration(proxyBeanMethods = false)
public class ClientConfigWebConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(ClientConfigWebConfig.class);

    private final ClientConfigProperties properties;

    public ClientConfigWebConfig(ClientConfigProperties properties) {
        this.properties = properties;
        if (properties.web().maintenance()) log.warn("Maintenance mode is ON: /api/v1/** answers 503 MAINTENANCE_MODE");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MaintenanceInterceptor(properties.web().maintenance(),
                        ClientConfigService.maintenanceMessage(properties)))
                .addPathPatterns("/api/v1", "/api/v1/**")
                .excludePathPatterns("/api/client-config", "/actuator", "/actuator/**")
                .order(MaintenanceInterceptor.ORDER);
    }
}
