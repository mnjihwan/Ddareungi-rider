package com.ddareungi.global.metrics;

import com.ddareungi.global.resilience.CircuitBreaker;
import com.ddareungi.global.resilience.ProviderResilience;
import com.ddareungi.global.resilience.ResilientCaller;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * {@code ddareungi.provider.circuit.state{provider, state}}: 1 for the breaker's current state, 0 for the other two
 * (state = closed/open/half_open). An OPEN breaker whose open duration has elapsed reports half_open, as
 * {@link CircuitBreaker#state()} does.
 */
@Component
public class ProviderCircuitMetrics implements MeterBinder {
    public static final String NAME = "ddareungi.provider.circuit.state";

    private final ProviderResilience resilience;

    public ProviderCircuitMetrics(ProviderResilience resilience) {
        this.resilience = resilience;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (ResilientCaller caller : List.of(resilience.place(), resilience.walking(), resilience.bicycle())) {
            CircuitBreaker breaker = caller.breaker();
            if (breaker == null) continue;
            for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
                Gauge.builder(NAME, breaker, current -> current.state() == state ? 1 : 0)
                        .description("1 when the provider's circuit breaker is in this state")
                        .tag("provider", breaker.name())
                        .tag("state", state.name().toLowerCase(Locale.ROOT))
                        .register(registry);
            }
        }
    }
}
