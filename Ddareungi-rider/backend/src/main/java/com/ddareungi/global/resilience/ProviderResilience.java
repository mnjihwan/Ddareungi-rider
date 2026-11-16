package com.ddareungi.global.resilience;

import com.ddareungi.global.http.ProviderHttpProperties;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;

/**
 * The resilience state shared by all requests: one circuit breaker each for place search, walking and cycling routes,
 * and one bulkhead each for place and route calls (walking and cycling share the route provider's quota).
 */
@Component
public class ProviderResilience {
    private final ResilientCaller place;
    private final ResilientCaller walking;
    private final ResilientCaller bicycle;

    public ProviderResilience(ProviderHttpProperties http, ResilienceProperties settings) {
        var placeBulkhead = new Semaphore(settings.bulkhead().placeMaxConcurrentCalls());
        var routeBulkhead = new Semaphore(settings.bulkhead().routeMaxConcurrentCalls());
        place = new ResilientCaller("place", new CircuitBreaker("place", settings.circuitBreaker()), placeBulkhead,
                settings, http.place().totalTimeout());
        walking = new ResilientCaller("walking", new CircuitBreaker("walking", settings.circuitBreaker()), routeBulkhead,
                settings, http.route().totalTimeout());
        bicycle = new ResilientCaller("bicycle", new CircuitBreaker("bicycle", settings.circuitBreaker()), routeBulkhead,
                settings, http.route().totalTimeout());
    }

    /** Fresh state with default settings, for clients created without Spring. */
    public static ProviderResilience defaults() {
        return new ProviderResilience(ProviderHttpProperties.defaults(), ResilienceProperties.defaults());
    }

    public ResilientCaller place() { return place; }
    public ResilientCaller walking() { return walking; }
    public ResilientCaller bicycle() { return bicycle; }
}
