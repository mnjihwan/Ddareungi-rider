package com.ddareungi.realtime.infrastructure;

import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Exposes the one {@link RealtimeFreshnessPolicy} used by every profile (local sample data included). */
@Configuration(proxyBeanMethods = false)
public class RealtimeFreshnessConfiguration {
    @Bean
    RealtimeFreshnessPolicy realtimeFreshnessPolicy(RealtimeFreshnessProperties properties) {
        return properties.toPolicy(Clock.systemUTC());
    }
}
