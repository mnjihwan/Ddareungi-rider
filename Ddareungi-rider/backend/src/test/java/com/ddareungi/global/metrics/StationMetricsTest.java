package com.ddareungi.global.metrics;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.station.domain.RealtimeSummary;
import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.infrastructure.StationCatalogHealthIndicator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.health.contributor.Health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Realtime and catalog gauges: values, one summary per scrape, NaN while nothing is loaded. */
class StationMetricsTest {
    static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final StationSearchPort port = mock(StationSearchPort.class);

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    double gauge(String name, String... tags) {
        return registry.find(name).tags(tags).gauge().value();
    }

    @Test void realtimeGaugesShareOneSummaryPerScrape() {
        var clock = new MutableClock(NOW);
        when(port.realtimeSummary()).thenReturn(new RealtimeSummary(10, 6, 3, 1, NOW.minusSeconds(75), NOW));
        new RealtimeMetrics(port, clock).bindTo(registry);

        assertThat(gauge(RealtimeMetrics.AGE)).isEqualTo(75.0);
        assertThat(gauge(RealtimeMetrics.STATIONS, "status", "fresh")).isEqualTo(6);
        assertThat(gauge(RealtimeMetrics.STATIONS, "status", "stale")).isEqualTo(3);
        assertThat(gauge(RealtimeMetrics.STATIONS, "status", "unavailable")).isEqualTo(1);
        verify(port, times(1)).realtimeSummary();

        clock.advance(Duration.ofSeconds(15)); // next scrape
        assertThat(gauge(RealtimeMetrics.STATIONS, "status", "fresh")).isEqualTo(6);
        verify(port, times(2)).realtimeSummary();
        assertThat(registry.find(RealtimeMetrics.STATIONS).gauges()).hasSize(3)
                .allSatisfy(gauge -> assertThat(gauge.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("status"));
    }

    @Test void unknownLastSuccessOrUnloadedCatalogReadsNaN() {
        var clock = new MutableClock(NOW);
        when(port.realtimeSummary()).thenReturn(new RealtimeSummary(0, 0, 0, 0, null, NOW));
        new RealtimeMetrics(port, clock).bindTo(registry);
        assertThat(gauge(RealtimeMetrics.AGE)).isNaN();

        clock.advance(Duration.ofSeconds(5));
        when(port.realtimeSummary()).thenThrow(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "loading"));
        assertThat(gauge(RealtimeMetrics.STATIONS, "status", "fresh")).isNaN();
        assertThat(gauge(RealtimeMetrics.AGE)).isNaN();
    }

    @Test void catalogGaugesReadTheHealthDetails() {
        var indicator = mock(StationCatalogHealthIndicator.class);
        when(indicator.health()).thenReturn(Health.up().withDetail("loaded", true).withDetail("stations", 2_734)
                .withDetail("loadedAt", NOW.minusSeconds(300).toString()).build());
        var beans = new StaticListableBeanFactory();
        beans.addBean("stationCatalogHealthIndicator", indicator);
        beans.addBean("clock", Clock.fixed(NOW, ZoneOffset.UTC));
        new StationCatalogMetrics(beans.getBeanProvider(StationCatalogHealthIndicator.class), beans.getBeanProvider(Clock.class))
                .bindTo(registry);

        assertThat(gauge(StationCatalogMetrics.SIZE)).isEqualTo(2_734);
        assertThat(gauge(StationCatalogMetrics.AGE)).isEqualTo(300.0);

        when(indicator.health()).thenReturn(Health.down().withDetail("loaded", false).build());
        assertThat(gauge(StationCatalogMetrics.SIZE)).isNaN();
        assertThat(gauge(StationCatalogMetrics.AGE)).isNaN();
    }

    @Test void withoutACatalogNoCatalogGaugeIsRegistered() {
        var beans = new StaticListableBeanFactory();
        new StationCatalogMetrics(beans.getBeanProvider(StationCatalogHealthIndicator.class), beans.getBeanProvider(Clock.class))
                .bindTo(registry);
        assertThat(registry.find(StationCatalogMetrics.SIZE).gauge()).isNull();
    }
}
