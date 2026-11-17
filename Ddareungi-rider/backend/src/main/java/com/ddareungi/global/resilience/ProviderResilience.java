package com.ddareungi.global.resilience;

import com.ddareungi.global.http.ProviderHttpProperties;
import com.ddareungi.global.metrics.ProviderCallMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;

/**
 * The resilience state shared by all requests: one circuit breaker each for place search, walking and cycling routes,
 * and one bulkhead each for place and route calls (walking and cycling share the route provider's quota).
 * In the application context every call is also timed into {@code ddareungi.provider.calls} (phase 3 O8).
 */
@Component
public class ProviderResilience {
    private final ResilientCaller place;
    private final ResilientCaller walking;
    private final ResilientCaller bicycle;

    @Autowired
    public ProviderResilience(ProviderHttpProperties http, ResilienceProperties settings,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this(http, settings, meterRegistry.getIfUnique());
    }

    /** Without metrics (tests, clients created without Spring). */
    public ProviderResilience(ProviderHttpProperties http, ResilienceProperties settings) {
        this(http, settings, (MeterRegistry) null);
    }

    public ProviderResilience(ProviderHttpProperties http, ResilienceProperties settings,
                              @Nullable MeterRegistry meterRegistry) {
        var placeBulkhead = new Semaphore(settings.bulkhead().placeMaxConcurrentCalls());
        var routeBulkhead = new Semaphore(settings.bulkhead().routeMaxConcurrentCalls());
        place = new ResilientCaller("place", new CircuitBreaker("place", settings.circuitBreaker()), placeBulkhead,
                settings, http.place().totalTimeout(), http.place().readTimeout(),
                ProviderCallMetrics.of("place", meterRegistry));
        walking = new ResilientCaller("walking", new CircuitBreaker("walking", settings.circuitBreaker()), routeBulkhead,
                settings, http.route().totalTimeout(), http.route().readTimeout(),
                ProviderCallMetrics.of("walking", meterRegistry));
        bicycle = new ResilientCaller("bicycle", new CircuitBreaker("bicycle", settings.circuitBreaker()), routeBulkhead,
                settings, http.route().totalTimeout(), http.route().readTimeout(),
                ProviderCallMetrics.of("bicycle", meterRegistry));
    }

    /** Fresh state with default settings, for clients created without Spring. */
    public static ProviderResilience defaults() {
        return new ProviderResilience(ProviderHttpProperties.defaults(), ResilienceProperties.defaults());
    }

    public ResilientCaller place() { return place; }
    public ResilientCaller walking() { return walking; }
    public ResilientCaller bicycle() { return bicycle; }
}
