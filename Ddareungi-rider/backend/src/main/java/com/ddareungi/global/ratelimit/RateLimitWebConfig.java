package com.ddareungi.global.ratelimit;

import com.ddareungi.global.web.InterceptorOrder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * Registers {@link RateLimitInterceptor} for the API (phase 2 O3). Kept apart from {@code WebCorsConfig}: handler
 * interceptors run after the CORS handling Spring adds per handler mapping. Actuator lives on the management port
 * and is excluded explicitly as well. The places/routes groups use Redis only on the infra profile; the local profile
 * (no Redis) uses the instance memory limiter for every group.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitWebConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(RateLimitWebConfig.class);

    private final ObjectProvider<RateLimitInterceptor> interceptor;

    public RateLimitWebConfig(ObjectProvider<RateLimitInterceptor> interceptor) {
        this.interceptor = interceptor;
    }

    @Bean
    RateLimitInterceptor rateLimitInterceptor(RateLimitProperties properties, ObjectProvider<Clock> clockProvider,
                                              ObjectProvider<StringRedisTemplate> redis, Environment environment,
                                              ObjectProvider<MeterRegistry> meterRegistry) {
        Clock clock = clockProvider.getIfUnique(Clock::systemUTC);
        var local = new LocalTokenBucketRateLimiter(clock, properties.localMaxEntries(),
                properties.localCleanupInterval());
        StringRedisTemplate template = environment.acceptsProfiles(Profiles.of("infra")) ? redis.getIfUnique() : null;
        RateLimiter shared = template == null ? local
                : new RedisTokenBucketRateLimiter(template, local, clock, properties.redisFailureCooldown());
        log.info("Rate limit {}: stations={}, places={}, routes={}, places/routes store={}",
                properties.enabled() ? "enabled" : "disabled",
                properties.policy(RateLimitGroup.STATIONS), properties.policy(RateLimitGroup.PLACES),
                properties.policy(RateLimitGroup.ROUTES), template == null ? "memory" : "redis");
        return new RateLimitInterceptor(properties, local, shared, meterRegistry.getIfUnique());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Every path: classification uses the matched controller pattern, and unknown paths are not limited.
        registry.addInterceptor(interceptor.getObject())
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator", "/actuator/**")
                // Review L11: explicit (was the default 0), last of the API interceptors; see InterceptorOrder.
                .order(InterceptorOrder.RATE_LIMIT);
    }
}
