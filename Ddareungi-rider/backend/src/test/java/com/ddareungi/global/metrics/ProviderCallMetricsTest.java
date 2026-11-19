package com.ddareungi.global.metrics;

import com.ddareungi.global.http.ProviderHttpProperties;
import com.ddareungi.global.resilience.CircuitBreaker;
import com.ddareungi.global.resilience.ProviderCallException;
import com.ddareungi.global.resilience.ProviderResilience;
import com.ddareungi.global.resilience.ResilienceProperties;
import com.ddareungi.global.resilience.ResilientCaller;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code ddareungi.provider.calls}: every ResilientCaller call lands in exactly one outcome series. */
class ProviderCallMetricsTest {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ResilienceProperties settings = ResilienceProperties.defaults();

    ResilientCaller caller(CircuitBreaker breaker, Semaphore bulkhead, Duration budget) {
        return new ResilientCaller("walking", breaker, bulkhead, settings, budget, null,
                ProviderCallMetrics.of("walking", registry));
    }

    long count(String outcome) {
        Timer timer = registry.find(ProviderCallMetrics.NAME).tag("provider", "walking").tag("outcome", outcome).timer();
        assertThat(timer).as("timer for outcome %s", outcome).isNotNull();
        return timer.count();
    }

    @Test void allOutcomesAreRegisteredUpFrontWithFixedTags() {
        ProviderCallMetrics.of("place", registry);
        for (ProviderCallOutcome outcome : ProviderCallOutcome.values()) {
            Timer timer = registry.find(ProviderCallMetrics.NAME).tag("provider", "place").tag("outcome", outcome.tag()).timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isZero();
            assertThat(timer.getId().getTags()).extracting(tag -> tag.getKey()).containsExactlyInAnyOrder("provider", "outcome");
        }
    }

    @Test void classifiesSuccessClientServerAndTimeoutOutcomes() {
        var caller = caller(new CircuitBreaker("walking", settings.circuitBreaker()), new Semaphore(4), Duration.ofSeconds(3));

        assertThat(caller.call(() -> "ok", false)).isEqualTo("ok");
        assertThatThrownBy(() -> caller.call(() -> { throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "quota", null, null, null); }, false))
                .isInstanceOf(HttpClientErrorException.class);
        assertThatThrownBy(() -> caller.call(() -> { throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad", null, null, null); }, false))
                .isInstanceOf(HttpServerErrorException.class);
        assertThatThrownBy(() -> caller.call(() -> { throw new ResourceAccessException("refused"); }, false))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> caller.call(() -> { throw new ResourceAccessException("read", new SocketTimeoutException("read")); }, false))
                .isInstanceOfSatisfying(ProviderCallException.class, error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.TIMEOUT));

        assertThat(count("success")).isEqualTo(1);
        assertThat(count("client_error")).isEqualTo(1);
        assertThat(count("server_error")).isEqualTo(2); // 502 and connection refused
        assertThat(count("timeout")).isEqualTo(1);
    }

    @Test void deadlineTimeoutIsATimeout() {
        var caller = caller(new CircuitBreaker("walking", settings.circuitBreaker()), new Semaphore(4), Duration.ofMillis(100));
        assertThatThrownBy(() -> caller.call(() -> {
            try { Thread.sleep(2_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return "late";
        }, false)).isInstanceOf(ProviderCallException.class);
        assertThat(count("timeout")).isEqualTo(1);
        assertThat(registry.find(ProviderCallMetrics.NAME).tag("outcome", "timeout").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(90);
    }

    @Test void rejectionsByOpenBreakerAndFullBulkheadAreCounted() {
        var breaker = new CircuitBreaker("walking", settings.circuitBreaker());
        for (int i = 0; i < 1_000 && breaker.state() != CircuitBreaker.State.OPEN; i++) breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> caller(breaker, new Semaphore(4), Duration.ofSeconds(3)).call(() -> "never", false))
                .isInstanceOfSatisfying(ProviderCallException.class, error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.CIRCUIT_OPEN));

        var full = caller(new CircuitBreaker("walking", settings.circuitBreaker()), new Semaphore(0), Duration.ofSeconds(3));
        assertThatThrownBy(() -> full.call(() -> "never", false))
                .isInstanceOfSatisfying(ProviderCallException.class, error -> assertThat(error.kind()).isEqualTo(ProviderCallException.Kind.BULKHEAD_FULL));

        assertThat(count("circuit_open")).isEqualTo(1);
        assertThat(count("bulkhead_full")).isEqualTo(1);
        assertThat(count("success")).isZero();
    }

    @Test void withoutARegistryNothingIsRecorded() {
        var resilience = new ProviderResilience(ProviderHttpProperties.defaults(), settings);
        assertThat(resilience.place().call(() -> "ok", false)).isEqualTo("ok");
        assertThat(ProviderCallOutcome.of(null)).isEqualTo(ProviderCallOutcome.SUCCESS);
    }

    @Test void providerResilienceTagsEachProvider() {
        var resilience = new ProviderResilience(ProviderHttpProperties.defaults(), settings, registry);
        resilience.bicycle().call(() -> "ok", false);
        assertThat(registry.find(ProviderCallMetrics.NAME).tag("provider", "bicycle").tag("outcome", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.find(ProviderCallMetrics.NAME).tag("provider", "walking").tag("outcome", "success").timer().count())
                .isZero();
        assertThat(registry.find(ProviderCallMetrics.NAME).tag("provider", "place").timers()).hasSize(ProviderCallOutcome.values().length);
    }

    @Test void circuitStateGaugeReportsOneForTheCurrentState() {
        var resilience = new ProviderResilience(ProviderHttpProperties.defaults(), settings);
        new ProviderCircuitMetrics(resilience).bindTo(registry);
        assertThat(registry.find(ProviderCircuitMetrics.NAME).tag("provider", "place").tag("state", "closed").gauge().value()).isEqualTo(1);
        assertThat(registry.find(ProviderCircuitMetrics.NAME).tag("provider", "place").tag("state", "open").gauge().value()).isZero();
        var breaker = resilience.place().breaker();
        for (int i = 0; i < 1_000 && breaker.state() != CircuitBreaker.State.OPEN; i++) breaker.onFailure();
        assertThat(registry.find(ProviderCircuitMetrics.NAME).tag("provider", "place").tag("state", "open").gauge().value()).isEqualTo(1);
        assertThat(registry.find(ProviderCircuitMetrics.NAME).tag("provider", "place").tag("state", "closed").gauge().value()).isZero();
        assertThat(registry.find(ProviderCircuitMetrics.NAME).tag("provider", "walking").tag("state", "closed").gauge().value()).isEqualTo(1);
        assertThat(registry.find(ProviderCircuitMetrics.NAME).gauges()).hasSize(9);
    }
}
